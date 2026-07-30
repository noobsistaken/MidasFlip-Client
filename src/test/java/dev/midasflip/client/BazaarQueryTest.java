package dev.midasflip.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The craft board's bazaar legs interpolate an item name into a command
 * string. The names come from our own board, but an interpolated command is
 * exactly the wrong place to trust an upstream string: one newline or "/"
 * would be a second command, sent under a single physical click. That would
 * break the one-click-one-action rule without anyone touching the send path.
 *
 * <p>Assertions here are EXACT-output, not "does not contain X". The first
 * version of this file used containment checks and one of them was written
 * against a NUL byte instead of a space, so it could never fail: a vacuous
 * assertion guarding the newest send surface, sitting green (found in review
 * 2026-07-30). Equality cannot rot the same way.
 */
class BazaarQueryTest {

    @Test
    void ordinaryItemNamesSurviveIntact() {
        // Spaces, apostrophes and hyphens are all legitimate in item names.
        assertEquals("Enchanted Diamond Block",
                ActionController.sanitizeQuery("Enchanted Diamond Block"));
        assertEquals("Builder's Wand", ActionController.sanitizeQuery("Builder's Wand"));
        assertEquals("Super-Heavy Runite", ActionController.sanitizeQuery("Super-Heavy Runite"));
        assertEquals("Enchanted Lapis Lazuli Block",
                ActionController.sanitizeQuery("Enchanted Lapis Lazuli Block"));
    }

    @Test
    void everySeparatorThatCouldEndACommandIsRemoved() {
        // Built from CODE POINTS, not string literals. Java expands \\uXXXX in
        // the lexer, so "\\u0022" would become a bare quote and not compile;
        // and the first version of this file picked up a stray NUL where a
        // space was meant, leaving an assertion that could never fail. Numbers
        // cannot be mangled by either mechanism.
        //
        // Note what is NOT here: space (0x20) and apostrophe (0x27) are
        // legitimate in item names ("Enchanted Diamond Block", "Builder's
        // Wand") and are covered by ordinaryItemNamesSurviveIntact. This list
        // is only characters that could end one command and begin another, or
        // reorder how the rest is displayed.
        int[] separators = {
            0x2F,   // /   command prefix
            0x3B,   // ;   separator
            0x0D,   // CR
            0x0A,   // LF
            0x09,   // tab
            0x5C,   // backslash
            0x7C,   // |
            0x26,   // &
            0x22,   // "
            0x60,   // `
            0x24,   // $
            0x00,   // NUL
            0x0B,   // vertical tab
            0x85,   // NEL
            0xA7,   // section sign (formatting)
            0x202E, // RTL override
            0x2028, // line separator
            0xFEFF, // BOM
            0xFF0F, // fullwidth solidus
        };
        for (int cp : separators) {
            String c = String.valueOf((char) cp);
            String out = ActionController.sanitizeQuery("Diamond" + c + "Block");
            assertFalse(out.contains(c),
                    String.format("separator U+%04X survived: %s", cp, out));
            assertEquals("Diamond Block", out,
                    String.format("unexpected result for U+%04X", cp));
        }
    }

    @Test
    void aSecondCommandCannotBeSmuggled() {
        assertEquals("Diamond gamemode", ActionController.sanitizeQuery("Diamond /gamemode"));
        assertEquals("Diamond op me", ActionController.sanitizeQuery("Diamond;op me"));
        assertEquals("Diamond kill", ActionController.sanitizeQuery("Diamond\r\nkill"));
        assertEquals("Diamond msg Bob hi", ActionController.sanitizeQuery("Diamond\nmsg Bob hi"));
    }

    @Test
    void sectionSignsCannotInjectFormatting() {
        assertEquals("Diamond cBlock", ActionController.sanitizeQuery("Diamond §cBlock"));
    }

    @Test
    void lengthIsCappedSoACommandCannotBeFlooded() {
        String out = ActionController.sanitizeQuery("A".repeat(500));
        assertTrue(out.length() <= 48, "length " + out.length());
    }

    @Test
    void emptyAndUnusableNamesYieldNothingToSend() {
        // The caller refuses to build a command from an empty result, so this
        // is the gate that stops a bare "/bz " with no argument going out.
        assertEquals("", ActionController.sanitizeQuery(null));
        assertEquals("", ActionController.sanitizeQuery(""));
        assertEquals("", ActionController.sanitizeQuery("   "));
        assertEquals("", ActionController.sanitizeQuery("§§§"));
        assertEquals("", ActionController.sanitizeQuery("\n\n"));
        assertEquals("", ActionController.sanitizeQuery("///"));
    }

    @Test
    void whitespaceIsCollapsedNotPreserved() {
        assertEquals("Enchanted Diamond",
                ActionController.sanitizeQuery("  Enchanted    Diamond  "));
    }
}
