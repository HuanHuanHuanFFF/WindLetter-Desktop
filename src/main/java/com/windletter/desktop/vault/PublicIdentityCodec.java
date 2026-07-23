package com.windletter.desktop.vault;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Strict UTF-8 JSON codec for the WindLetter Desktop public identity format. */
final class PublicIdentityCodec {

    private static final String FORMAT = "windletter.public-identity";
    private static final int VERSION = 1;
    private static final int MAX_JSON_BYTES = 256 * 1024;
    private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Base64.Encoder ENCODER =
        Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final ObjectMapper MAPPER = createMapper();
    private final PublicIdentityArmorCodec armor =
        new PublicIdentityArmorCodec();

    String encode(VaultIdentity identity) throws PublicIdentityException {
        Objects.requireNonNull(identity, "identity");
        try {
            List<PublicKeyDto> keys = new ArrayList<>();
            for (VaultPrivateKey key : identity.keys()) {
                keys.add(toDto(key));
            }
            String encoded = MAPPER.writeValueAsString(new PublicIdentityDto(
                FORMAT,
                VERSION,
                identity.displayName(),
                keys
            ));
            if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
                throw new IllegalArgumentException(
                    "public identity exceeds the supported size"
                );
            }
            return encoded;
        } catch (IOException | RuntimeException failure) {
            throw new PublicIdentityException();
        }
    }

    String encodeArmored(
        VaultIdentity identity,
        PublicIdentityArmorCodec.Format format
    ) throws PublicIdentityException {
        return armor.encode(encode(identity), format);
    }

    PublicIdentity decodeExchange(String encoded)
        throws PublicIdentityException {
        Objects.requireNonNull(encoded, "encoded");
        return decode(armor.hasExactHeader(encoded)
            ? armor.decode(encoded)
            : encoded);
    }

    PublicIdentity decode(String encoded) throws PublicIdentityException {
        Objects.requireNonNull(encoded, "encoded");
        try {
            if (encoded.isEmpty()
                || encoded.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
                throw new IllegalArgumentException(
                    "public identity has an invalid size"
                );
            }
            PublicIdentityDto dto = MAPPER.readValue(
                encoded,
                PublicIdentityDto.class
            );
            if (dto == null
                || !FORMAT.equals(dto.format())
                || dto.version() != VERSION
                || dto.keys() == null
                || dto.keys().size() != VaultKeyAlgorithm.values().length) {
                throw new IllegalArgumentException("public identity root is invalid");
            }

            String displayName = VaultModelChecks.displayName(
                dto.displayName(),
                "displayName"
            );
            if (!displayName.equals(dto.displayName())) {
                throw new IllegalArgumentException(
                    "public identity displayName is not canonical"
                );
            }

            List<VaultPublicKey> keys = new ArrayList<>();
            for (int index = 0; index < dto.keys().size(); index++) {
                keys.add(fromDto(
                    dto.keys().get(index),
                    VaultKeyAlgorithm.values()[index]
                ));
            }
            return new PublicIdentity(displayName, keys);
        } catch (IOException | RuntimeException failure) {
            throw new PublicIdentityException();
        }
    }

    private static PublicKeyDto toDto(VaultPrivateKey key) {
        byte[] kid = null;
        byte[] publicKey = null;
        try {
            kid = key.kid();
            publicKey = key.publicKey();
            return new PublicKeyDto(
                key.algorithm().wireName(),
                key.algorithm().publicEncoding(),
                ENCODER.encodeToString(kid),
                ENCODER.encodeToString(publicKey)
            );
        } finally {
            clear(publicKey);
            clear(kid);
        }
    }

    private static VaultPublicKey fromDto(
        PublicKeyDto dto,
        VaultKeyAlgorithm expectedAlgorithm
    ) {
        if (dto == null
            || !expectedAlgorithm.wireName().equals(dto.algorithm())
            || !expectedAlgorithm.publicEncoding().equals(dto.encoding())) {
            throw new IllegalArgumentException(
                "public identity key algorithm or encoding is invalid"
            );
        }

        byte[] kid = null;
        byte[] publicKey = null;
        byte[] derivedKid = null;
        try {
            kid = decodeCanonical(dto.kid(), "kid");
            publicKey = decodeCanonical(dto.publicKey(), "publicKey");
            VaultModelChecks.requireExact(
                kid,
                VaultModelChecks.KID_BYTES,
                "kid"
            );
            VaultModelChecks.requireExact(
                publicKey,
                expectedAlgorithm.publicKeyBytes(),
                "publicKey"
            );
            derivedKid = VaultKeyIds.derive(expectedAlgorithm, publicKey);
            if (!MessageDigest.isEqual(kid, derivedKid)) {
                throw new IllegalArgumentException("public identity KID is invalid");
            }
            return new VaultPublicKey(expectedAlgorithm, kid, publicKey);
        } finally {
            clear(derivedKid);
            clear(publicKey);
            clear(kid);
        }
    }

    private static byte[] decodeCanonical(String value, String fieldName) {
        if (value == null || !BASE64URL.matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + " is not Base64URL");
        }
        byte[] decoded = DECODER.decode(value);
        if (!ENCODER.encodeToString(decoded).equals(value)) {
            clear(decoded);
            throw new IllegalArgumentException(fieldName + " is not canonical");
        }
        return decoded;
    }

    private static ObjectMapper createMapper() {
        JsonFactory factory = JsonFactory.builder()
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

    private record PublicIdentityDto(
        String format,
        int version,
        String displayName,
        List<PublicKeyDto> keys
    ) {
    }

    private record PublicKeyDto(
        String algorithm,
        String encoding,
        String kid,
        String publicKey
    ) {
    }
}
