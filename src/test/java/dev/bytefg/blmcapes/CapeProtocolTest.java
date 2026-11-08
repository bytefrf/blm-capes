package dev.bytefg.blmcapes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CapeProtocolTest {
    private static final UUID REQUESTED = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID UNREQUESTED = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void acceptsEveryKnownBuiltin() {
        for (String id : CapeProtocol.BUILTIN_IDS) {
            CapeAssignment assignment = CapeProtocol.assignmentFor(id);
            assertEquals(id, assignment.builtinId());
            assertFalse(assignment.isCustom());
        }
    }

    @Test
    void acceptsOnlyLowercaseCustomSha256Code() {
        CapeAssignment assignment = CapeProtocol.assignmentFor("custom_" + HASH);
        assertEquals(HASH, assignment.customHash());
        assertEquals("custom_" + HASH, assignment.code());
        assertNull(CapeProtocol.assignmentFor("custom_" + HASH.toUpperCase()));
        assertNull(CapeProtocol.assignmentFor("custom_" + HASH.substring(1)));
        assertNull(CapeProtocol.assignmentFor("../../textures/evil"));
    }

    @Test
    void parsesOnlyRequestedValidatedCapeAssignments() throws IOException {
        String response = "{\"success\":true,\"version\":2,\"capes\":{"
                + "\"" + REQUESTED + "\":\"custom_" + HASH + "\","
                + "\"" + UNREQUESTED + "\":\"dragon\","
                + "\"not-a-uuid\":\"blm\","
                + "\"00000000-0000-0000-0000-000000000001\":\"not-public\"}}";
        Map<UUID, CapeAssignment> result = CapeProtocol.parseResponse(response, List.of(REQUESTED));
        assertEquals(1, result.size());
        assertEquals(HASH, result.get(REQUESTED).customHash());
    }

    @Test
    void rejectsMissingOrWrongProtocolVersion() {
        assertThrows(IOException.class, () -> CapeProtocol.parseResponse(
                "{\"success\":true,\"capes\":{}}", List.of(REQUESTED)));
        assertThrows(IOException.class, () -> CapeProtocol.parseResponse(
                "{\"success\":true,\"version\":1,\"capes\":{}}", List.of(REQUESTED)));
        assertThrows(IOException.class, () -> CapeProtocol.parseResponse(
                "{\"success\":false,\"version\":2,\"capes\":{}}", List.of(REQUESTED)));
    }

    @Test
    void validatesCustomPngHeaderBeforeNativeDecode() throws IOException {
        byte[] validHeader = new byte[33];
        byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        System.arraycopy(signature, 0, validHeader, 0, signature.length);
        validHeader[12] = 'I';
        validHeader[13] = 'H';
        validHeader[14] = 'D';
        validHeader[15] = 'R';
        validHeader[19] = 64;
        validHeader[23] = 32;
        CustomCapeTextures.validatePngHeader(validHeader);

        validHeader[23] = 31;
        assertThrows(IOException.class, () -> CustomCapeTextures.validatePngHeader(validHeader));
    }
}
