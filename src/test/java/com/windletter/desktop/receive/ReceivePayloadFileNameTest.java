package com.windletter.desktop.receive;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReceivePayloadFileNameTest {

    @Test
    void shouldInferCommonExtensionsFromMimeTypes() {
        assertEquals(
            "recovered-payload.pdf",
            ReceivePayloadFileName.suggestedName("application/pdf")
        );
        assertEquals(
            "recovered-payload.png",
            ReceivePayloadFileName.suggestedName("image/png")
        );
        assertEquals(
            "recovered-payload.json",
            ReceivePayloadFileName.suggestedName(
                "application/problem+json; charset=UTF-8"
            )
        );
        assertEquals(
            "recovered-payload.txt",
            ReceivePayloadFileName.suggestedName(
                "TEXT/PLAIN; charset=UTF-8"
            )
        );
        assertEquals(
            "recovered-payload.docx",
            ReceivePayloadFileName.suggestedName(
                "application/vnd.openxmlformats-officedocument"
                    + ".wordprocessingml.document"
            )
        );
    }

    @Test
    void shouldUseBinaryExtensionWhenMimeTypeIsNotReliable() {
        assertEquals(
            "recovered-payload.bin",
            ReceivePayloadFileName.suggestedName(
                "application/octet-stream"
            )
        );
        assertEquals(
            "recovered-payload.bin",
            ReceivePayloadFileName.suggestedName(
                "application/x-private-unknown"
            )
        );
    }
}
