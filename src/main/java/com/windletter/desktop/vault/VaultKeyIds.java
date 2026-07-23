package com.windletter.desktop.vault;

import com.windletter.protocol.key.Ed25519KeyId;
import com.windletter.protocol.key.MLKem768KeyId;
import com.windletter.protocol.key.X25519KeyId;

import java.util.Base64;

/** Converts the core protocol's canonical Base64URL KIDs to Vault byte strings. */
final class VaultKeyIds {

    private VaultKeyIds() {
    }

    static byte[] derive(
        VaultKeyAlgorithm algorithm,
        byte[] publicKey
    ) {
        String encoded = switch (algorithm) {
            case X25519 -> X25519KeyId.derive(publicKey);
            case ML_KEM_768 -> MLKem768KeyId.derive(publicKey);
            case ED25519 -> Ed25519KeyId.derive(publicKey);
        };
        return Base64.getUrlDecoder().decode(encoded);
    }
}
