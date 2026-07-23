package com.windletter.desktop.vault;

import java.util.List;

/** Public, non-secret identity material decoded from the product JSON format. */
final class PublicIdentity {

    private final String displayName;
    private final List<VaultPublicKey> publicKeys;

    PublicIdentity(
        String displayName,
        List<VaultPublicKey> publicKeys
    ) {
        this.displayName = VaultModelChecks.displayName(
            displayName,
            "displayName"
        );
        this.publicKeys = VaultModelChecks.boundedList(
            publicKeys,
            3,
            "publicKeys"
        );
        VaultModelChecks.requireAlgorithmOrder(this.publicKeys, "publicKeys");
    }

    String displayName() {
        return displayName;
    }

    List<VaultPublicKey> publicKeys() {
        return publicKeys;
    }
}
