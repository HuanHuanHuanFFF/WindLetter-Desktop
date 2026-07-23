package com.windletter.desktop.vault;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Strict canonical CBOR codec for complete decrypted V1 Vault state. */
final class VaultPayloadCodec {

    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_PAYLOAD_BYTES = 8 * 1024 * 1024;
    private static final ObjectMapper MAPPER = createMapper();

    byte[] encode(VaultPayload payload) throws VaultPayloadException {
        Objects.requireNonNull(payload, "payload");
        PayloadDto dto = null;
        byte[] encoded = null;
        boolean success = false;
        try {
            dto = toDto(payload);
            encoded = MAPPER.writeValueAsBytes(dto);
            if (encoded.length == 0 || encoded.length > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("Vault payload exceeds the supported size");
            }
            success = true;
            return encoded;
        } catch (IOException | RuntimeException failure) {
            throw new VaultPayloadException();
        } finally {
            if (!success) {
                clear(encoded);
            }
            clear(dto);
        }
    }

    VaultPayload decode(
        byte[] encoded,
        byte[] expectedVaultId
    ) throws VaultPayloadException {
        Objects.requireNonNull(encoded, "encoded");
        Objects.requireNonNull(expectedVaultId, "expectedVaultId");

        byte[] owned = encoded.clone();
        byte[] canonical = null;
        PayloadDto dto = null;
        JsonNode tree = null;
        VaultPayload payload = null;
        boolean success = false;
        try {
            if (owned.length == 0 || owned.length > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("Vault payload has an invalid size");
            }
            if (expectedVaultId.length != VaultModelChecks.VAULT_ID_BYTES) {
                throw new IllegalArgumentException("expectedVaultId has an invalid length");
            }

            tree = MAPPER.readTree(owned);
            validateTreeShape(tree);
            dto = MAPPER.treeToValue(tree, PayloadDto.class);
            canonical = MAPPER.writeValueAsBytes(dto);
            if (!java.security.MessageDigest.isEqual(owned, canonical)) {
                throw new IllegalArgumentException("Vault payload is not canonical");
            }

            payload = fromDto(dto);
            byte[] actualVaultId = payload.vaultId();
            try {
                if (!java.security.MessageDigest.isEqual(
                    expectedVaultId,
                    actualVaultId
                )) {
                    throw new IllegalArgumentException("Vault IDs do not match");
                }
            } finally {
                clear(actualVaultId);
            }
            success = true;
            return payload;
        } catch (IOException | RuntimeException failure) {
            throw new VaultPayloadException();
        } finally {
            if (!success && payload != null) {
                payload.close();
            }
            clear(dto);
            clearBinaryTree(tree);
            clear(canonical);
            clear(owned);
        }
    }

    private static PayloadDto toDto(VaultPayload payload) {
        List<IdentityDto> identities = new ArrayList<>();
        List<ContactDto> contacts = new ArrayList<>();
        try {
            for (VaultIdentity identity : payload.identities()) {
                identities.add(toDto(identity));
            }
            for (VaultContact contact : payload.contacts()) {
                contacts.add(toDto(contact));
            }
            VaultSettings settings = payload.settings();
            return new PayloadDto(
                SCHEMA_VERSION,
                payload.vaultId(),
                payload.createdAt().toString(),
                payload.updatedAt().toString(),
                identities,
                contacts,
                new SettingsDto(
                    settings.defaultIdentityId() == null
                        ? null
                        : settings.defaultIdentityId().toString(),
                    settings.autoLockMinutes()
                )
            );
        } catch (RuntimeException failure) {
            identities.forEach(VaultPayloadCodec::clear);
            contacts.forEach(VaultPayloadCodec::clear);
            throw failure;
        }
    }

    private static IdentityDto toDto(VaultIdentity identity) {
        List<PrivateKeyDto> keys = new ArrayList<>();
        try {
            for (VaultPrivateKey key : identity.keys()) {
                keys.add(toDto(key));
            }
            return new IdentityDto(
                identity.identityId().toString(),
                identity.displayName(),
                identity.note(),
                identity.origin().name(),
                identity.createdAt().toString(),
                identity.updatedAt().toString(),
                keys
            );
        } catch (RuntimeException failure) {
            keys.forEach(VaultPayloadCodec::clear);
            throw failure;
        }
    }

    private static PrivateKeyDto toDto(VaultPrivateKey key) {
        return new PrivateKeyDto(
            key.algorithm().wireName(),
            key.algorithm().privateEncoding(),
            key.kid(),
            key.publicKey(),
            key.privateKey()
        );
    }

    private static ContactDto toDto(VaultContact contact) {
        List<PublicKeyDto> keys = new ArrayList<>();
        try {
            for (VaultPublicKey key : contact.publicKeys()) {
                keys.add(toDto(key));
            }
            return new ContactDto(
                contact.contactId().toString(),
                contact.claimedDisplayName(),
                contact.localDisplayName(),
                contact.note(),
                contact.verificationStatus().name(),
                contact.verifiedAt() == null ? null : contact.verifiedAt().toString(),
                contact.addedAt().toString(),
                contact.updatedAt().toString(),
                keys
            );
        } catch (RuntimeException failure) {
            keys.forEach(VaultPayloadCodec::clear);
            throw failure;
        }
    }

    private static PublicKeyDto toDto(VaultPublicKey key) {
        return new PublicKeyDto(
            key.algorithm().wireName(),
            key.algorithm().publicEncoding(),
            key.kid(),
            key.publicKey()
        );
    }

    private static VaultPayload fromDto(PayloadDto dto) {
        if (dto == null
            || dto.schemaVersion() != SCHEMA_VERSION
            || dto.settings() == null) {
            throw new IllegalArgumentException("Vault payload root is invalid");
        }

        List<VaultIdentity> identities = new ArrayList<>();
        try {
            for (IdentityDto identity : requireList(dto.identities(), "identities")) {
                identities.add(fromDto(identity));
            }
            List<VaultContact> contacts = new ArrayList<>();
            for (ContactDto contact : requireList(dto.contacts(), "contacts")) {
                contacts.add(fromDto(contact));
            }
            UUID defaultIdentityId = dto.settings().defaultIdentityId() == null
                ? null
                : parseUuid(dto.settings().defaultIdentityId(), "defaultIdentityId");
            return new VaultPayload(
                dto.vaultId(),
                parseInstant(dto.createdAt(), "createdAt"),
                parseInstant(dto.updatedAt(), "updatedAt"),
                identities,
                contacts,
                new VaultSettings(
                    defaultIdentityId,
                    dto.settings().autoLockMinutes()
                )
            );
        } catch (RuntimeException failure) {
            identities.forEach(VaultIdentity::close);
            throw failure;
        }
    }

    private static VaultIdentity fromDto(IdentityDto dto) {
        if (dto == null) {
            throw new IllegalArgumentException("identity is null");
        }
        List<VaultPrivateKey> keys = new ArrayList<>();
        try {
            for (PrivateKeyDto key : requireList(dto.keys(), "keys")) {
                keys.add(fromDto(key));
            }
            VaultIdentity identity = new VaultIdentity(
                parseUuid(dto.identityId(), "identityId"),
                dto.displayName(),
                dto.note(),
                parseEnum(VaultIdentityOrigin.class, dto.origin(), "origin"),
                parseInstant(dto.createdAt(), "createdAt"),
                parseInstant(dto.updatedAt(), "updatedAt"),
                keys
            );
            if (!identity.displayName().equals(dto.displayName())) {
                identity.close();
                throw new IllegalArgumentException("displayName is not canonical");
            }
            return identity;
        } catch (RuntimeException failure) {
            keys.forEach(VaultPrivateKey::close);
            throw failure;
        }
    }

    private static VaultPrivateKey fromDto(PrivateKeyDto dto) {
        if (dto == null) {
            throw new IllegalArgumentException("private key is null");
        }
        VaultKeyAlgorithm algorithm = VaultKeyAlgorithm.fromWireName(dto.algorithm());
        if (!algorithm.privateEncoding().equals(dto.encoding())) {
            throw new IllegalArgumentException("private key encoding is invalid");
        }
        return new VaultPrivateKey(
            algorithm,
            dto.kid(),
            dto.publicKey(),
            dto.privateKey()
        );
    }

    private static VaultContact fromDto(ContactDto dto) {
        if (dto == null) {
            throw new IllegalArgumentException("contact is null");
        }
        List<VaultPublicKey> keys = new ArrayList<>();
        for (PublicKeyDto key : requireList(dto.publicKeys(), "publicKeys")) {
            keys.add(fromDto(key));
        }
        VaultContact contact = new VaultContact(
            parseUuid(dto.contactId(), "contactId"),
            dto.claimedDisplayName(),
            dto.localDisplayName(),
            dto.note(),
            parseEnum(
                VaultVerificationStatus.class,
                dto.verificationStatus(),
                "verificationStatus"
            ),
            dto.verifiedAt() == null
                ? null
                : parseInstant(dto.verifiedAt(), "verifiedAt"),
            parseInstant(dto.addedAt(), "addedAt"),
            parseInstant(dto.updatedAt(), "updatedAt"),
            keys
        );
        if (!contact.claimedDisplayName().equals(dto.claimedDisplayName())
            || !Objects.equals(
                contact.localDisplayName(),
                dto.localDisplayName()
            )) {
            throw new IllegalArgumentException("contact display name is not canonical");
        }
        return contact;
    }

    private static VaultPublicKey fromDto(PublicKeyDto dto) {
        if (dto == null) {
            throw new IllegalArgumentException("public key is null");
        }
        VaultKeyAlgorithm algorithm = VaultKeyAlgorithm.fromWireName(dto.algorithm());
        if (!algorithm.publicEncoding().equals(dto.encoding())) {
            throw new IllegalArgumentException("public key encoding is invalid");
        }
        return new VaultPublicKey(
            algorithm,
            dto.kid(),
            dto.publicKey()
        );
    }

    private static void validateTreeShape(JsonNode root) throws IOException {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Vault payload must be a map");
        }
        requireBinary(root.path("vaultId"), "vaultId");
        JsonNode identities = requireArray(
            root.path("identities"),
            VaultModelChecks.MAX_IDENTITIES,
            "identities"
        );
        for (JsonNode identity : identities) {
            JsonNode keys = requireArray(identity.path("keys"), 3, "keys");
            if (keys.size() != 3) {
                throw new IllegalArgumentException("identity keys must contain three entries");
            }
            for (JsonNode key : keys) {
                requireBinary(key.path("kid"), "kid");
                requireBinary(key.path("publicKey"), "publicKey");
                requireBinary(key.path("privateKey"), "privateKey");
            }
        }
        JsonNode contacts = requireArray(
            root.path("contacts"),
            VaultModelChecks.MAX_CONTACTS,
            "contacts"
        );
        for (JsonNode contact : contacts) {
            JsonNode keys = requireArray(contact.path("publicKeys"), 3, "publicKeys");
            if (keys.size() != 3) {
                throw new IllegalArgumentException(
                    "contact publicKeys must contain three entries"
                );
            }
            for (JsonNode key : keys) {
                requireBinary(key.path("kid"), "kid");
                requireBinary(key.path("publicKey"), "publicKey");
            }
        }
    }

    private static JsonNode requireArray(
        JsonNode node,
        int maximum,
        String fieldName
    ) {
        if (!node.isArray() || node.size() > maximum) {
            throw new IllegalArgumentException(fieldName + " is invalid");
        }
        return node;
    }

    private static void requireBinary(JsonNode node, String fieldName)
        throws IOException {
        if (!node.isBinary() || node.binaryValue() == null) {
            throw new IllegalArgumentException(fieldName + " must be a CBOR byte string");
        }
    }

    private static Instant parseInstant(String value, String fieldName) {
        try {
            Instant parsed = Instant.parse(Objects.requireNonNull(value, fieldName));
            if (!parsed.toString().equals(value)) {
                throw new IllegalArgumentException(fieldName + " is not canonical");
            }
            return parsed;
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException(fieldName + " is invalid");
        }
    }

    private static UUID parseUuid(String value, String fieldName) {
        try {
            UUID parsed = UUID.fromString(Objects.requireNonNull(value, fieldName));
            if (!parsed.toString().equals(value)) {
                throw new IllegalArgumentException(fieldName + " is not canonical");
            }
            return parsed;
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(fieldName + " is invalid");
        }
    }

    private static <E extends Enum<E>> E parseEnum(
        Class<E> type,
        String value,
        String fieldName
    ) {
        try {
            return Enum.valueOf(type, Objects.requireNonNull(value, fieldName));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(fieldName + " is invalid");
        }
    }

    private static <T> List<T> requireList(List<T> value, String fieldName) {
        if (value == null || value.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(fieldName + " is invalid");
        }
        return value;
    }

    private static ObjectMapper createMapper() {
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

    private static void clear(PayloadDto dto) {
        if (dto == null) {
            return;
        }
        clear(dto.vaultId());
        if (dto.identities() != null) {
            dto.identities().forEach(VaultPayloadCodec::clear);
        }
        if (dto.contacts() != null) {
            dto.contacts().forEach(VaultPayloadCodec::clear);
        }
    }

    private static void clear(IdentityDto identity) {
        if (identity != null && identity.keys() != null) {
            identity.keys().forEach(VaultPayloadCodec::clear);
        }
    }

    private static void clear(ContactDto contact) {
        if (contact != null && contact.publicKeys() != null) {
            contact.publicKeys().forEach(VaultPayloadCodec::clear);
        }
    }

    private static void clear(PrivateKeyDto key) {
        if (key != null) {
            clear(key.kid());
            clear(key.publicKey());
            clear(key.privateKey());
        }
    }

    private static void clear(PublicKeyDto key) {
        if (key != null) {
            clear(key.kid());
            clear(key.publicKey());
        }
    }

    private static void clearBinaryTree(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isBinary()) {
            try {
                clear(node.binaryValue());
            } catch (IOException ignored) {
                // Best effort only; no secret material is added to the failure.
            }
            return;
        }
        node.elements().forEachRemaining(VaultPayloadCodec::clearBinaryTree);
    }

    private static void clear(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    private record PayloadDto(
        int schemaVersion,
        byte[] vaultId,
        String createdAt,
        String updatedAt,
        List<IdentityDto> identities,
        List<ContactDto> contacts,
        SettingsDto settings
    ) {
    }

    private record IdentityDto(
        String identityId,
        String displayName,
        String note,
        String origin,
        String createdAt,
        String updatedAt,
        List<PrivateKeyDto> keys
    ) {
    }

    private record PrivateKeyDto(
        String algorithm,
        String encoding,
        byte[] kid,
        byte[] publicKey,
        byte[] privateKey
    ) {
    }

    private record ContactDto(
        String contactId,
        String claimedDisplayName,
        String localDisplayName,
        String note,
        String verificationStatus,
        String verifiedAt,
        String addedAt,
        String updatedAt,
        List<PublicKeyDto> publicKeys
    ) {
    }

    private record PublicKeyDto(
        String algorithm,
        String encoding,
        byte[] kid,
        byte[] publicKey
    ) {
    }

    private record SettingsDto(
        String defaultIdentityId,
        int autoLockMinutes
    ) {
    }
}
