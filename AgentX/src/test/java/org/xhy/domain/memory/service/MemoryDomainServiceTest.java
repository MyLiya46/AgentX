package org.xhy.domain.memory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.xhy.domain.memory.model.CandidateMemory;
import org.xhy.domain.memory.model.MemoryItemEntity;
import org.xhy.domain.memory.model.MemoryType;
import org.xhy.domain.memory.repository.MemoryItemRepository;
import org.xhy.domain.rag.model.ModelConfig;
import org.xhy.infrastructure.exception.BusinessException;
import org.xhy.infrastructure.rag.factory.EmbeddingModelFactory;
import org.xhy.infrastructure.rag.service.UserModelConfigResolver;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("MemoryDomainService 单元测试")
class MemoryDomainServiceTest {

    @Mock
    MemoryItemRepository memoryItemRepository;
    @Mock
    UserModelConfigResolver userModelConfigResolver;
    @Mock
    EmbeddingStore<TextSegment> memoryEmbeddingStore;
    @Mock
    OpenAiEmbeddingModel embeddingModel;

    private MemoryDomainService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // 使用匿名内部类替代 @Mock EmbeddingModelFactory，避免 Java 26 + ByteBuddy 兼容性问题
        EmbeddingModelFactory embeddingModelFactory = new EmbeddingModelFactory() {
            @Override
            public OpenAiEmbeddingModel createEmbeddingModel(EmbeddingConfig config) {
                return embeddingModel;
            }
        };

        service = new MemoryDomainService(memoryItemRepository, embeddingModelFactory, userModelConfigResolver,
                memoryEmbeddingStore);

        // 配置通用 mock 行为
        ModelConfig cfg = new ModelConfig();
        cfg.setApiKey("test-key");
        cfg.setBaseUrl("http://test");
        cfg.setModelEndpoint("/embeddings");
        lenient().when(userModelConfigResolver.getUserEmbeddingModelConfig(anyString())).thenReturn(cfg);
        lenient().when(embeddingModel.embed(any(TextSegment.class)))
                .thenReturn(Response.from(Embedding.from(new float[1024])));
    }

    @Nested
    @DisplayName("saveMemories — 错误处理")
    class SaveMemoriesErrorHandling {

        @Test
        @DisplayName("insert 失败时应抛 BusinessException，且不写入向量库")
        void shouldThrowExceptionAndSkipVectorWhenInsertFails() {
            CandidateMemory cm = new CandidateMemory();
            cm.setType(MemoryType.FACT);
            cm.setText("用户的编程偏好");

            when(memoryItemRepository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            doThrow(new RuntimeException("数据库连接丢失")).when(memoryItemRepository).insert(any(MemoryItemEntity.class));

            assertThatThrownBy(() -> service.saveMemories("user-1", null, List.of(cm)))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("记忆保存失败");

            // 验证向量库未被调用 — 避免孤儿记录
            verify(memoryEmbeddingStore, never()).add(any(Embedding.class), any(TextSegment.class));
        }

        @Test
        @DisplayName("向量写入失败时应补偿删除业务表记录")
        void shouldCompensateDeleteWhenVectorWriteFails() {
            CandidateMemory cm = new CandidateMemory();
            cm.setType(MemoryType.FACT);
            cm.setText("用户偏好 Python");

            when(memoryItemRepository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            // 模拟 MyBatis-Plus 的 UUID 自动生成：insert 后给 entity 设置 ID
            doAnswer(inv -> {
                MemoryItemEntity entity = inv.getArgument(0);
                entity.setId("generated-id-001");
                return 1;
            }).when(memoryItemRepository).insert(any(MemoryItemEntity.class));
            doThrow(new RuntimeException("向量库不可用")).when(memoryEmbeddingStore).add(any(Embedding.class),
                    any(TextSegment.class));

            assertThatThrownBy(() -> service.saveMemories("user-1", null, List.of(cm)))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("向量入库失败");

            // 验证补偿删除被调用
            verify(memoryItemRepository).deleteById("generated-id-001");
        }

        @Test
        @DisplayName("补偿删除本身失败时不应掩盖原始异常")
        void shouldNotMaskOriginalExceptionWhenCompensationFails() {
            CandidateMemory cm = new CandidateMemory();
            cm.setType(MemoryType.FACT);
            cm.setText("测试内容");

            when(memoryItemRepository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            doAnswer(inv -> {
                MemoryItemEntity entity = inv.getArgument(0);
                entity.setId("generated-id-002");
                return 1;
            }).when(memoryItemRepository).insert(any(MemoryItemEntity.class));
            doThrow(new RuntimeException("向量库不可用")).when(memoryEmbeddingStore).add(any(Embedding.class),
                    any(TextSegment.class));
            doThrow(new RuntimeException("补偿删除失败")).when(memoryItemRepository).deleteById(anyString());

            // 原始异常（向量写入失败）应被抛出，而非补偿删除的异常
            assertThatThrownBy(() -> service.saveMemories("user-1", null, List.of(cm)))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("向量入库失败");
        }
    }

    @Nested
    @DisplayName("saveMemories — 去重与合并")
    class SaveMemoriesDeduplication {

        @Test
        @DisplayName("重复记忆应合并而非新增")
        void shouldMergeDuplicateMemory() {
            CandidateMemory cm = new CandidateMemory();
            cm.setType(MemoryType.FACT);
            cm.setText("用户偏好中文回答");
            cm.setImportance(0.8f);
            cm.setTags(List.of("偏好"));

            // 模拟已存在同 hash 的记忆
            MemoryItemEntity existed = new MemoryItemEntity();
            existed.setId("existing-id");
            existed.setUserId("user-1");
            existed.setType("FACT");
            existed.setText("用户偏好中文");
            existed.setImportance(0.7f);
            existed.setTags(List.of("旧标签"));
            existed.setStatus(1);

            when(memoryItemRepository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existed);
            when(memoryItemRepository.updateById(any(MemoryItemEntity.class))).thenReturn(1);

            List<String> ids = service.saveMemories("user-1", null, List.of(cm));

            assertThat(ids).containsExactly("existing-id");

            // 验证合并后的 importance 为 max(0.7, 0.8) = 0.8
            ArgumentCaptor<MemoryItemEntity> captor = ArgumentCaptor.forClass(MemoryItemEntity.class);
            verify(memoryItemRepository).updateById(captor.capture());
            assertThat(captor.getValue().getImportance()).isEqualTo(0.8f);
        }
    }

    @Nested
    @DisplayName("findOrphanVectors — 孤儿检测")
    class OrphanVectors {

        @Test
        @DisplayName("应正确检测孤儿记录")
        void shouldDetectOrphanVectors() {
            when(memoryItemRepository.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of()); // 无 active 记录
            when(memoryItemRepository.findOrphanVectorItemIds(eq("user-1"), anySet()))
                    .thenReturn(List.of("orphan-1", "orphan-2"));

            List<String> orphans = service.findOrphanVectors("user-1");

            assertThat(orphans).containsExactly("orphan-1", "orphan-2");
        }

        @Test
        @DisplayName("无孤儿记录时返回空列表")
        void shouldReturnEmptyWhenNoOrphans() {
            MemoryItemEntity item = new MemoryItemEntity();
            item.setId("item-1");
            item.setStatus(1);

            when(memoryItemRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(item));
            when(memoryItemRepository.findOrphanVectorItemIds(eq("user-1"), eq(Set.of("item-1"))))
                    .thenReturn(List.of());

            List<String> orphans = service.findOrphanVectors("user-1");

            assertThat(orphans).isEmpty();
        }
    }

    @Nested
    @DisplayName("cleanOrphanVectors — 孤儿清理")
    class CleanOrphanVectors {

        @Test
        @DisplayName("应正确清理孤儿记录")
        void shouldCleanOrphanVectors() {
            when(memoryItemRepository.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of());
            when(memoryItemRepository.findOrphanVectorItemIds(eq("user-1"), anySet()))
                    .thenReturn(List.of("orphan-1", "orphan-2"));
            when(memoryItemRepository.deleteVectorByItemId(anyString(), anyString())).thenReturn(1);

            int cleaned = service.cleanOrphanVectors("user-1");

            assertThat(cleaned).isEqualTo(2);
            verify(memoryItemRepository).deleteVectorByItemId("user-1", "orphan-1");
            verify(memoryItemRepository).deleteVectorByItemId("user-1", "orphan-2");
        }

        @Test
        @DisplayName("清理失败时不应中断其他记录的清理")
        void shouldContinueCleaningWhenSingleRecordFails() {
            when(memoryItemRepository.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of());
            when(memoryItemRepository.findOrphanVectorItemIds(eq("user-1"), anySet()))
                    .thenReturn(List.of("orphan-1", "orphan-2"));
            doThrow(new RuntimeException("删除失败"))
                    .when(memoryItemRepository).deleteVectorByItemId("user-1", "orphan-1");
            when(memoryItemRepository.deleteVectorByItemId("user-1", "orphan-2")).thenReturn(1);

            int cleaned = service.cleanOrphanVectors("user-1");

            // orphan-1 失败，orphan-2 成功
            assertThat(cleaned).isEqualTo(1);
        }
    }
}
