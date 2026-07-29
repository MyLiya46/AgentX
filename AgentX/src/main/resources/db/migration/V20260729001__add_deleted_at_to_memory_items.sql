-- 为 memory_items 表添加 deleted_at 列（通用审计列，对齐其他表结构）
-- 业务软删除仍由 status 字段管理（1=active, 0=archived）
ALTER TABLE public.memory_items
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITHOUT TIME ZONE;

COMMENT ON COLUMN public.memory_items.deleted_at IS '删除时间戳（审计用，业务软删除由 status 字段管理）';
