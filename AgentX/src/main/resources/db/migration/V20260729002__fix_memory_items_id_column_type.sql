-- 修复 memory_items.id 列类型：从 PostgreSQL uuid 改为 varchar(64)
-- 项目使用 MyBatis-Plus IdType.ASSIGN_UUID 生成 32 位 hex 字符串
-- uuid 类型拒绝隐式转换 varchar，导致 insert 失败
ALTER TABLE public.memory_items
    ALTER COLUMN id TYPE varchar(64);

COMMENT ON COLUMN public.memory_items.id IS '记忆条目 ID（MyBatis-Plus ASSIGN_UUID 生成的 32 位 hex 字符串）';
