package com.example.ragassistant.rag;

import com.example.ragassistant.TestFixtures;
import com.example.ragassistant.config.RagProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class TextChunkerTest {
    private final TextChunker chunker = new TextChunker(TestFixtures.properties(Path.of(".")));

    @Test void overlappingChunksPreserveWholeTextAndBoundaries() {
        String text = "中文制度😀".repeat(53);
        var chunks = chunker.split(text);
        StringBuilder rebuilt = new StringBuilder(chunks.getFirst().text());
        for (int i = 1; i < chunks.size(); i++) {
            var previous = chunks.get(i - 1);
            var current = chunks.get(i);
            assertThat(current.start()).isEqualTo(previous.end() - 20);
            assertThat(current.text().codePointCount(0, current.text().length())).isLessThanOrEqualTo(100);
            rebuilt.append(current.text().substring(current.text().offsetByCodePoints(0, 20)));
        }
        assertThat(rebuilt.toString()).isEqualTo(text);
    }

    @Test void emptyOrWhitespaceHasNoChunks() {
        assertThat(chunker.split("")).isEmpty();
        assertThat(chunker.split(" \n\t ")).isEmpty();
    }

    @Test void exactBoundaryDoesNotProduceDuplicateTail() {
        assertThat(chunker.split("中".repeat(100))).hasSize(1);
        assertThat(chunker.split("中".repeat(101))).hasSize(2);
    }

    @Test void invalidOverlapCannotCreateInfiniteLoop() {
        assertThatThrownBy(() -> new RagProperties(Path.of("."), 100, 100, 5, .5, 10000, 2000, 4))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
