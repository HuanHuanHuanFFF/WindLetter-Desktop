package com.windletter.desktop.vault;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import org.bouncycastle.crypto.PBEParametersGenerator;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/**
 * Encrypts and decrypts a complete versioned Vault payload.
 * The caller owns and must clear returned plaintext arrays after use.
 */
final class VaultCipher {

    private static final byte[] MAGIC = new byte[]{
        'W', 'L', 'V', 'A', 'U', 'L', 'T', '1'
    };
    private static final String FORMAT = "windletter.desktop.vault";
    private static final String KDF_ALGORITHM = "ARGON2ID";
    private static final String AEAD_ALGORITHM = "AES-256-GCM";
    private static final int FORMAT_VERSION = 1;
    private static final int VAULT_ID_BYTES = VaultModelChecks.VAULT_ID_BYTES;
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int KEY_BYTES = 32;
    private static final int TAG_BYTES = 16;
    private static final int LENGTH_BYTES = Integer.BYTES;
    private static final int MAX_HEADER_BYTES = 4_096;
    private static final int MAX_PLAINTEXT_BYTES = 8 * 1024 * 1024;
    private static final int MAX_ENVELOPE_BYTES = MAX_PLAINTEXT_BYTES + MAX_HEADER_BYTES + 1_024;
    private static final ObjectMapper HEADER_MAPPER = createHeaderMapper();

    private final SecureRandom secureRandom;

    VaultCipher() {
        this(new SecureRandom());
    }

    VaultCipher(SecureRandom secureRandom) {
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
    }

    byte[] seal(
        byte[] plaintext,
        byte[] vaultId,
        char[] password,
        VaultKdfParameters parameters
    ) throws VaultWriteException {
        Objects.requireNonNull(plaintext, "plaintext");
        Objects.requireNonNull(vaultId, "vaultId");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(parameters, "parameters");
        if (plaintext.length > MAX_PLAINTEXT_BYTES) {
            throw new IllegalArgumentException("plaintext exceeds the supported Vault size");
        }
        if (password.length == 0) {
            throw new IllegalArgumentException("password must not be empty");
        }
        if (vaultId.length != VAULT_ID_BYTES) {
            throw new IllegalArgumentException("vaultId must contain exactly 16 bytes");
        }

        byte[] ownedVaultId = vaultId.clone();
        byte[] salt = randomBytes(SALT_BYTES);
        byte[] nonce = randomBytes(NONCE_BYTES);
        byte[] passwordBytes = null;
        byte[] key = null;
        byte[] headerBytes = null;
        byte[] aad = null;
        byte[] ciphertext = null;
        try {
            VaultHeader header = new VaultHeader(
                FORMAT,
                FORMAT_VERSION,
                ownedVaultId,
                new KdfHeader(
                    KDF_ALGORITHM,
                    salt,
                    parameters.memoryKiB(),
                    parameters.iterations(),
                    parameters.parallelism(),
                    KEY_BYTES
                ),
                new AeadHeader(AEAD_ALGORITHM, nonce)
            );
            headerBytes = HEADER_MAPPER.writeValueAsBytes(header);
            if (headerBytes.length == 0 || headerBytes.length > MAX_HEADER_BYTES) {
                throw new IllegalStateException("Vault header is outside the supported size");
            }

            aad = encodePrefix(headerBytes);
            passwordBytes = PBEParametersGenerator.PKCS5PasswordToUTF8Bytes(password);
            key = VaultKeyDerivation.derive(passwordBytes, salt, parameters);
            ciphertext = crypt(Cipher.ENCRYPT_MODE, key, nonce, aad, plaintext);

            byte[] envelope = new byte[aad.length + ciphertext.length];
            System.arraycopy(aad, 0, envelope, 0, aad.length);
            System.arraycopy(ciphertext, 0, envelope, aad.length, ciphertext.length);
            return envelope;
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            throw new VaultWriteException();
        } finally {
            clear(ciphertext);
            clear(key);
            clear(passwordBytes);
            clear(aad);
            clear(headerBytes);
            clear(nonce);
            clear(salt);
            clear(ownedVaultId);
        }
    }

    OpenedVault open(byte[] envelope, char[] password) throws VaultOpenException {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(password, "password");

        byte[] ownedEnvelope = envelope.clone();
        byte[] headerBytes = null;
        byte[] canonicalHeader = null;
        byte[] aad = null;
        byte[] ciphertext = null;
        byte[] passwordBytes = null;
        byte[] key = null;
        byte[] plaintext = null;
        VaultHeader header = null;
        try {
            ParsedEnvelope parsed = parseEnvelope(ownedEnvelope);
            headerBytes = parsed.headerBytes();
            aad = parsed.aad();
            ciphertext = parsed.ciphertext();

            header = HEADER_MAPPER.readValue(headerBytes, VaultHeader.class);
            validateHeader(header);
            canonicalHeader = HEADER_MAPPER.writeValueAsBytes(header);
            if (!MessageDigest.isEqual(headerBytes, canonicalHeader)) {
                throw new IllegalArgumentException("Vault header is not canonical");
            }

            VaultKdfParameters parameters = new VaultKdfParameters(
                header.kdf().memoryKiB(),
                header.kdf().iterations(),
                header.kdf().parallelism()
            );
            passwordBytes = PBEParametersGenerator.PKCS5PasswordToUTF8Bytes(password);
            key = VaultKeyDerivation.derive(
                passwordBytes,
                header.kdf().salt(),
                parameters
            );
            plaintext = crypt(
                Cipher.DECRYPT_MODE,
                key,
                header.aead().nonce(),
                aad,
                ciphertext
            );
            OpenedVault opened = new OpenedVault(header.vaultId(), plaintext);
            return opened;
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            throw new VaultOpenException();
        } finally {
            clear(plaintext);
            clear(key);
            clear(passwordBytes);
            clear(ciphertext);
            clear(aad);
            clear(canonicalHeader);
            clear(headerBytes);
            clear(ownedEnvelope);
            clearHeader(header);
        }
    }

    private byte[] randomBytes(int length) {
        byte[] value = new byte[length];
        secureRandom.nextBytes(value);
        return value;
    }

    private static ParsedEnvelope parseEnvelope(byte[] envelope) {
        int minimumSize = MAGIC.length + LENGTH_BYTES + 1 + TAG_BYTES;
        if (envelope.length < minimumSize || envelope.length > MAX_ENVELOPE_BYTES) {
            throw new IllegalArgumentException("Vault envelope has an invalid size");
        }

        ByteBuffer input = ByteBuffer.wrap(envelope).order(ByteOrder.BIG_ENDIAN);
        byte[] magic = new byte[MAGIC.length];
        input.get(magic);
        try {
            if (!MessageDigest.isEqual(MAGIC, magic)) {
                throw new IllegalArgumentException("Vault magic is invalid");
            }
        } finally {
            clear(magic);
        }

        int headerLength = input.getInt();
        if (headerLength <= 0
            || headerLength > MAX_HEADER_BYTES
            || input.remaining() < headerLength + TAG_BYTES) {
            throw new IllegalArgumentException("Vault header length is invalid");
        }

        byte[] headerBytes = new byte[headerLength];
        input.get(headerBytes);
        byte[] ciphertext = new byte[input.remaining()];
        input.get(ciphertext);
        byte[] aad = Arrays.copyOf(envelope, MAGIC.length + LENGTH_BYTES + headerLength);
        return new ParsedEnvelope(headerBytes, aad, ciphertext);
    }

    private static byte[] encodePrefix(byte[] headerBytes) {
        ByteBuffer output = ByteBuffer.allocate(
            MAGIC.length + LENGTH_BYTES + headerBytes.length
        ).order(ByteOrder.BIG_ENDIAN);
        output.put(MAGIC);
        output.putInt(headerBytes.length);
        output.put(headerBytes);
        return output.array();
    }

    private static byte[] crypt(
        int mode,
        byte[] key,
        byte[] nonce,
        byte[] aad,
        byte[] input
    ) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
            mode,
            new SecretKeySpec(key, "AES"),
            new GCMParameterSpec(TAG_BYTES * Byte.SIZE, nonce)
        );
        cipher.updateAAD(aad);
        return cipher.doFinal(input);
    }

    private static void validateHeader(VaultHeader header) {
        if (header == null
            || !FORMAT.equals(header.format())
            || header.formatVersion() != FORMAT_VERSION
            || header.vaultId() == null
            || header.vaultId().length != VAULT_ID_BYTES
            || header.kdf() == null
            || !KDF_ALGORITHM.equals(header.kdf().algorithm())
            || header.kdf().salt() == null
            || header.kdf().salt().length != SALT_BYTES
            || header.kdf().outputBytes() != KEY_BYTES
            || header.aead() == null
            || !AEAD_ALGORITHM.equals(header.aead().algorithm())
            || header.aead().nonce() == null
            || header.aead().nonce().length != NONCE_BYTES) {
            throw new IllegalArgumentException("Vault header fields are invalid");
        }
    }

    private static ObjectMapper createHeaderMapper() {
        CBORFactory factory = CBORFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
        ObjectMapper mapper = new ObjectMapper(factory);
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mapper.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        mapper.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
        mapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        return mapper;
    }

    private static void clear(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    private static void clearHeader(VaultHeader header) {
        if (header != null) {
            clear(header.vaultId());
            if (header.kdf() != null) {
                clear(header.kdf().salt());
            }
            if (header.aead() != null) {
                clear(header.aead().nonce());
            }
        }
    }

    private record KdfHeader(
        String algorithm,
        byte[] salt,
        int memoryKiB,
        int iterations,
        int parallelism,
        int outputBytes
    ) {
    }

    private record AeadHeader(String algorithm, byte[] nonce) {
    }

    private record VaultHeader(
        String format,
        int formatVersion,
        byte[] vaultId,
        KdfHeader kdf,
        AeadHeader aead
    ) {
    }

    private record ParsedEnvelope(byte[] headerBytes, byte[] aad, byte[] ciphertext) {
    }
}
