package cn.apcraft.pass;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PassTypeTest {

    @Test
    void parseIsCaseInsensitiveAndTrimmed() {
        assertEquals(PassType.MONTHLY, PassType.parse("MONTHLY"));
        assertEquals(PassType.STUDENT, PassType.parse("Student"));
        assertEquals(PassType.MONTHLY, PassType.parse(" monthly "));
    }

    @Test
    void parseRejectsUnknown() {
        assertNull(PassType.parse("annual"));
        assertNull(PassType.parse(null));
    }
}
