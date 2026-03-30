package dev.oakheart.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for sequence-of-maps support (list items that are maps).
 */
class SequenceOfMapsTest {

    @Test
    void parseSimpleSequenceOfMaps() {
        ConfigManager config = ConfigManager.fromString("""
                drops:
                  - id: zombie_pet
                    chance: 0.001
                  - id: skeleton_pet
                    chance: 0.002
                """);

        assertTrue(config.contains("drops"));

        // drops is a SEQUENCE, not a MAP — access items via the node tree
        YamlNode drops = config.getDocument().getRoot().getChild("drops");
        assertNotNull(drops);
        assertEquals(NodeType.SEQUENCE, drops.getType());
        assertEquals(2, drops.getItems().size());
    }

    @Test
    void roundTripSequenceOfMaps() {
        String yaml = """
                drops:
                  - id: zombie_pet
                    chance: 0.001
                    type: NEXO
                  - id: skeleton_pet
                    chance: 0.002
                    type: VANILLA""";

        YamlDocument doc = YamlParser.parse(yaml);
        assertEquals(yaml, doc.serialize());
    }

    @Test
    void roundTripWithCommentsBetweenItems() {
        String yaml = """
                drops:
                  - id: first
                    chance: 0.1

                  # Second drop
                  - id: second
                    chance: 0.2""";

        YamlDocument doc = YamlParser.parse(yaml);
        assertEquals(yaml, doc.serialize());
    }

    @Test
    void mapItemWithNestedSequence() {
        String yaml = """
                mobs:
                  - id: zombie
                    commands:
                      - "say hello"
                      - "give diamond"
                  - id: skeleton
                    commands:
                      - "say bye\"""";

        YamlDocument doc = YamlParser.parse(yaml);
        assertEquals(yaml, doc.serialize());
    }

    @Test
    void mapItemWithInlineComment() {
        String yaml = """
                drops:
                  - id: rare_drop
                    chance: 0.001  # 0.1%
                    type: NEXO""";

        YamlDocument doc = YamlParser.parse(yaml);
        assertEquals(yaml, doc.serialize());
    }

    @Test
    void mixedSequenceFormatsInSameFile() {
        // OakMobDrops uses both: sequence-of-maps for some mobs, named maps for others
        String yaml = """
                simple-list:
                  - apple
                  - banana
                map-list:
                  - id: first
                    value: 1
                  - id: second
                    value: 2
                named-map:
                  first:
                    value: 1""";

        YamlDocument doc = YamlParser.parse(yaml);
        assertEquals(yaml, doc.serialize());
    }

    @Test
    void accessMapItemFields() {
        ConfigManager config = ConfigManager.fromString("""
                drops:
                  - id: zombie_pet
                    chance: 0.001
                    type: NEXO
                    amount: 1
                  - id: skeleton_pet
                    chance: 0.002
                    type: VANILLA
                    amount: 3
                """);

        // The items are MAP nodes in a SEQUENCE
        YamlNode dropsNode = config.getDocument().getRoot().getChild("drops");
        assertNotNull(dropsNode);
        assertEquals(NodeType.SEQUENCE, dropsNode.getType());

        List<YamlNode> items = dropsNode.getItems();
        assertEquals(2, items.size());

        // First item is a MAP
        YamlNode first = items.get(0);
        assertEquals(NodeType.MAP, first.getType());
        assertEquals("zombie_pet", first.getChild("id").asString(""));
        assertEquals(0.001, first.getChild("chance").asDouble(0));
        assertEquals("NEXO", first.getChild("type").asString(""));
        assertEquals(1, first.getChild("amount").asInt(0));

        // Second item
        YamlNode second = items.get(1);
        assertEquals(NodeType.MAP, second.getType());
        assertEquals("skeleton_pet", second.getChild("id").asString(""));
        assertEquals(3, second.getChild("amount").asInt(0));
    }

    @Test
    void deeplyNestedMapInSequence() {
        String yaml = """
                mobs:
                  ZOMBIE:
                    drops:
                      - id: pet
                        chance: 0.001
                        lore:
                          - "line one"
                          - "line two\"""";

        YamlDocument doc = YamlParser.parse(yaml);
        assertEquals(yaml, doc.serialize());
    }

    @Test
    void emptyValueInMapItem() {
        ConfigManager config = ConfigManager.fromString("""
                items:
                  - name: test
                    description:
                """);

        YamlNode items = config.getDocument().getRoot().getChild("items");
        assertNotNull(items);
        YamlNode first = items.getItems().get(0);
        assertEquals(NodeType.MAP, first.getType());
        assertEquals("test", first.getChild("name").asString(""));
        assertNull(first.getChild("description").getRawValue());
    }
}
