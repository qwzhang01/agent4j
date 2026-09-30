package io.github.qwzhang01.agent.rag.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkTest {

    private static Chunk chunk(String title, List<String> path) {
        return new Chunk("d.md#0", "d.md", title, null, path, "body", 1, 1, null, null);
    }

    @Test
    void titleEqualToFirstHeadingIsNotRepeated() {
        assertEquals("指南 > 安装\nbody", chunk("指南", List.of("指南", "安装")).contextualText());
    }

    @Test
    void distinctTitleIsPrepended() {
        assertEquals("Guide | 安装\nbody", chunk("Guide", List.of("安装")).contextualText());
    }

    @Test
    void noSectionPathUsesTitleOnly() {
        assertEquals("Guide\nbody", chunk("Guide", List.of()).contextualText());
    }
}
