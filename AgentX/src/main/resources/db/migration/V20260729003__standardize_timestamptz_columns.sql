-- ===========================================================================
-- 统一所有表的时间戳列为 TIMESTAMP WITH TIME ZONE (TIMESTAMPTZ)
--
-- 背景：
--   数据库实际列类型为 TIMESTAMPTZ，但 Java 代码使用 LocalDateTime（无时区），
--   导致 JDBC 抛出 PSQLException: Cannot convert TIMESTAMPTZ to LocalDateTime。
--
-- 方案：
--   1. 代码侧：BaseEntity 字段类型从 LocalDateTime 改为 OffsetDateTime（JDBC 标准映射）
--   2. 数据库侧：本脚本确保所有时间戳列统一为 TIMESTAMPTZ（PostgreSQL 官方推荐）
--
-- PostgreSQL 内部以 UTC 存储 TIMESTAMPTZ 值，读取时通过会话 timezone 转换。
-- JDBC 连接配置 ?TimeZone=Asia/Shanghai 确保 OffsetDateTime 读回正确的 +08:00 偏移。
--
-- 幂等设计：仅当列类型为 timestamp without time zone 时才执行 ALTER。
-- 风险：低（AT TIME ZONE 保留相同时刻，无数据丢失）。
-- 执行时机：低峰期（每个 ALTER 毫秒级）。
-- ===========================================================================

DO $$
DECLARE
    r RECORD;
    converted_count INT := 0;
    skipped_count INT := 0;
BEGIN
    FOR r IN
        SELECT table_name, column_name
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND column_name IN (
              'created_at', 'updated_at', 'deleted_at',
              'expired_at', 'paid_at', 'cancelled_at', 'refunded_at',
              'last_accessed_at', 'last_login_at'
          )
          AND data_type = 'timestamp without time zone'
        ORDER BY table_name, column_name
    LOOP
        EXECUTE format(
            'ALTER TABLE public.%I ALTER COLUMN %I TYPE timestamptz USING %I AT TIME ZONE ''Asia/Shanghai''',
            r.table_name, r.column_name, r.column_name
        );
        converted_count := converted_count + 1;
        RAISE NOTICE '[V20260729003] 已转换: %.% → TIMESTAMPTZ', r.table_name, r.column_name;
    END LOOP;

    IF converted_count = 0 THEN
        RAISE NOTICE '[V20260729003] 所有时间戳列已是 TIMESTAMPTZ，无需转换';
    ELSE
        RAISE NOTICE '[V20260729003] 转换完成: % 列已改为 TIMESTAMPTZ', converted_count;
    END IF;
END $$;
