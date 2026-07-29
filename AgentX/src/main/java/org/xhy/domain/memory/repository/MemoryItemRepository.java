package org.xhy.domain.memory.repository;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.xhy.domain.memory.model.MemoryItemEntity;
import org.xhy.infrastructure.repository.MyBatisPlusExtRepository;

import java.util.List;
import java.util.Set;

/** memory_items 表数据访问（基于 MyBatis-Plus 提供通用 CRUD） */
@Mapper
public interface MemoryItemRepository extends MyBatisPlusExtRepository<MemoryItemEntity> {

    /** 查询向量库中的孤儿 ITEM_ID（不在给定 activeItemIds 集合中）
     *
     * @param userId 用户 ID
     * @param activeItemIds 该用户在 memory_items 中的 active 记录 ID 集合（若为空则跳过 IN 过滤）
     * @return 孤儿记录的 itemId 列表 */
    @Select("""
            <script>
            SELECT metadata->>'ITEM_ID' AS item_id
            FROM memory_vector_store
            WHERE metadata->>'USER_ID' = #{userId}
            <if test="activeItemIds != null and activeItemIds.size() > 0">
              AND metadata->>'ITEM_ID' NOT IN
              <foreach collection="activeItemIds" item="id" open="(" separator="," close=")">
                  #{id}
              </foreach>
            </if>
            </script>
            """)
    List<String> findOrphanVectorItemIds(@Param("userId") String userId,
            @Param("activeItemIds") Set<String> activeItemIds);

    /** 按 ITEM_ID 删除向量库中的记录
     *
     * @param userId 用户 ID
     * @param itemId 记忆条目 ID
     * @return 删除的行数 */
    @Delete("""
            DELETE FROM memory_vector_store
            WHERE metadata->>'USER_ID' = #{userId}
              AND metadata->>'ITEM_ID' = #{itemId}
            """)
    int deleteVectorByItemId(@Param("userId") String userId, @Param("itemId") String itemId);
}
