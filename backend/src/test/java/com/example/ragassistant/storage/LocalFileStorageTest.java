package com.example.ragassistant.storage;

import com.example.ragassistant.TestFixtures;
import com.example.ragassistant.web.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class LocalFileStorageTest {
    @TempDir Path temp;

    @Test void hostileOriginalFilenameCannotEscapeStorage() throws Exception {
        var storage = new LocalFileStorage(TestFixtures.properties(temp));
        var file = new MockMultipartFile("file", "..\\..\\policy.md", "text/plain", "中文".getBytes(StandardCharsets.UTF_8));
        var stored = storage.save(UUID.randomUUID().toString(), file);
        assertThat(stored.originalFilename()).isEqualTo("policy.md");
        assertThat(storage.resolve(stored.key()).getParent()).isEqualTo(temp);
        assertThat(stored.sha256()).hasSize(64);
        assertThat(Files.readString(storage.resolve(stored.key()))).isEqualTo("中文");
        assertThatThrownBy(() -> storage.resolve("../escape.md")).isInstanceOf(IllegalArgumentException.class);
        storage.delete(stored.key());
        storage.delete(stored.key()); // 删除幂等，便于失败重试。
    }

    @Test void rejectsUnsupportedExtensionAndEmptyFile() throws Exception {
        var storage = new LocalFileStorage(TestFixtures.properties(temp));
        assertThatThrownBy(() -> storage.save("id", new MockMultipartFile("file", "run.exe", "x", new byte[]{1})))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> storage.save("id", new MockMultipartFile("file", "empty.md", "text/plain", new byte[0])))
                .isInstanceOf(BusinessException.class);
    }
}
