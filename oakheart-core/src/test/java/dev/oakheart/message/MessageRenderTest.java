package dev.oakheart.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MessageManager#renderLines} — the lore line-rendering rules
 * behind {@code parseLines}. Exercised directly (no live server) since the logic
 * is pure MiniMessage + component handling.
 */
class MessageRenderTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Test
    void listFormProducesOneComponentPerLine() {
        List<Component> out = MessageManager.renderLines(
                List.of("<red>Status: Collected", "<yellow>Obtained: now"), null);

        assertEquals(2, out.size());
        assertEquals("Status: Collected", PLAIN.serialize(out.get(0)));
        assertEquals("Obtained: now", PLAIN.serialize(out.get(1)));
    }

    @Test
    void everyLineSuppressesItalic() {
        List<Component> out = MessageManager.renderLines(List.of("<red>line one", "line two"), null);

        for (Component line : out) {
            assertEquals(TextDecoration.State.FALSE, line.decoration(TextDecoration.ITALIC),
                    "lore lines must not inherit italic");
        }
    }

    @Test
    void emptyListFallsBackToScalar() {
        List<Component> out = MessageManager.renderLines(List.of(), "<green>single line");

        assertEquals(1, out.size());
        assertEquals("single line", PLAIN.serialize(out.get(0)));
    }

    @Test
    void nullListFallsBackToScalar() {
        List<Component> out = MessageManager.renderLines(null, "single line");

        assertEquals(1, out.size());
        assertEquals("single line", PLAIN.serialize(out.get(0)));
    }

    @Test
    void emptyListAndEmptyScalarIsDisabled() {
        assertTrue(MessageManager.renderLines(List.of(), "").isEmpty());
        assertTrue(MessageManager.renderLines(List.of(), null).isEmpty());
        assertTrue(MessageManager.renderLines(null, null).isEmpty());
    }

    @Test
    void listTakesPrecedenceOverScalarFallback() {
        // When a non-empty list is supplied, the scalar fallback is ignored.
        List<Component> out = MessageManager.renderLines(
                List.of("from list"), "from scalar");

        assertEquals(1, out.size());
        assertEquals("from list", PLAIN.serialize(out.get(0)));
    }

    @Test
    void blankEntryRendersAsSpacerLine() {
        List<Component> out = MessageManager.renderLines(
                List.of("top", "", "bottom"), null);

        assertEquals(3, out.size());
        assertEquals("", PLAIN.serialize(out.get(1)));
    }

    @Test
    void placeholdersAreResolvedPerLine() {
        List<Component> out = MessageManager.renderLines(
                List.of("Obtained: <date>"), null,
                Placeholder.unparsed("date", "2026-06-02"));

        assertEquals("Obtained: 2026-06-02", PLAIN.serialize(out.getFirst()));
    }
}
