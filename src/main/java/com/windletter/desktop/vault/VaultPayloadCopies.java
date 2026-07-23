package com.windletter.desktop.vault;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Deep copies mutable key arrays so candidate payloads have independent ownership. */
final class VaultPayloadCopies {

    private VaultPayloadCopies() {
    }

    static List<VaultIdentity> identities(List<VaultIdentity> source) {
        List<VaultIdentity> copies = new ArrayList<>();
        try {
            for (VaultIdentity identity : source) {
                copies.add(identity(identity));
            }
            return copies;
        } catch (RuntimeException failure) {
            copies.forEach(VaultIdentity::close);
            throw failure;
        }
    }

    static VaultIdentity identity(VaultIdentity source) {
        List<VaultPrivateKey> keys = new ArrayList<>();
        try {
            for (VaultPrivateKey key : source.keys()) {
                keys.add(privateKey(key));
            }
            return new VaultIdentity(
                source.identityId(),
                source.displayName(),
                source.note(),
                source.origin(),
                source.createdAt(),
                source.updatedAt(),
                keys
            );
        } catch (RuntimeException failure) {
            keys.forEach(VaultPrivateKey::close);
            throw failure;
        }
    }

    static List<VaultContact> contacts(List<VaultContact> source) {
        List<VaultContact> copies = new ArrayList<>();
        for (VaultContact contact : source) {
            copies.add(contact(contact));
        }
        return copies;
    }

    private static VaultPrivateKey privateKey(VaultPrivateKey source) {
        byte[] kid = null;
        byte[] publicKey = null;
        byte[] privateKey = null;
        try {
            kid = source.kid();
            publicKey = source.publicKey();
            privateKey = source.privateKey();
            return new VaultPrivateKey(
                source.algorithm(),
                kid,
                publicKey,
                privateKey
            );
        } finally {
            clear(privateKey);
            clear(publicKey);
            clear(kid);
        }
    }

    private static VaultContact contact(VaultContact source) {
        List<VaultPublicKey> keys = new ArrayList<>();
        for (VaultPublicKey key : source.publicKeys()) {
            keys.add(publicKey(key));
        }
        return new VaultContact(
            source.contactId(),
            source.claimedDisplayName(),
            source.localDisplayName(),
            source.note(),
            source.verificationStatus(),
            source.verifiedAt(),
            source.addedAt(),
            source.updatedAt(),
            keys
        );
    }

    private static VaultPublicKey publicKey(VaultPublicKey source) {
        byte[] kid = null;
        byte[] publicKey = null;
        try {
            kid = source.kid();
            publicKey = source.publicKey();
            return new VaultPublicKey(source.algorithm(), kid, publicKey);
        } finally {
            clear(publicKey);
            clear(kid);
        }
    }

    private static void clear(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}
