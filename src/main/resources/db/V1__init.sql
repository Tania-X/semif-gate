-- semif-gate 初始表结构（Postgres）
--
-- 两张表职责分明：
--   decision_cache  —— 可丢弃。它只是加速手段，丢了最多是重新算一遍。
--   decision_record —— 不可丢弃。它是「这条判定是怎么做出来的」的唯一证据。
-- 因此缓存条目允许 upsert 覆盖，而审计记录只增不改。

-- ---------------------------------------------------------------- 判定缓存
CREATE TABLE IF NOT EXISTS decision_cache (
    -- 查找键 = sha256(state_hash, point_ref)。
    -- 刻意【不含】registry_sha256 / options_sha256：它们作为列存下来，
    -- 在命中后做契约校验。若把它们放进主键，注册表一变条目就查不到，
    -- 校验逻辑会退化成死代码（详见 DecisionCache 的说明）。
    lookup_key      CHAR(64) PRIMARY KEY,
    state_hash      CHAR(64) NOT NULL,
    point_ref       TEXT     NOT NULL,
    decision_id     CHAR(64) NOT NULL,
    distribution    JSONB    NOT NULL,
    outcome         TEXT     NOT NULL,
    degraded_reason TEXT,
    provider_id     TEXT     NOT NULL,
    model_revision  TEXT     NOT NULL,
    backend         TEXT     NOT NULL,
    prompt_sha256   TEXT     NOT NULL,
    -- 契约指纹：命中后必须与当前值一致，否则视为未命中
    registry_sha256 TEXT     NOT NULL,
    options_sha256  TEXT     NOT NULL,
    input_tokens    INT      NOT NULL,
    latency_ms      BIGINT   NOT NULL,
    stored_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_decision_cache_state_point
    ON decision_cache (state_hash, point_ref);

-- ---------------------------------------------------------------- 判定审计
CREATE TABLE IF NOT EXISTS decision_record (
    decision_id        CHAR(64) PRIMARY KEY,
    decided_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    point_id           TEXT   NOT NULL,
    point_version      INT    NOT NULL,
    state_hash         CHAR(64) NOT NULL,
    state_json         JSONB  NOT NULL,
    distribution       JSONB  NOT NULL,
    argmax_option      TEXT   NOT NULL,
    max_probability    NUMERIC(8,6) NOT NULL,
    -- margin = 最高与次高之差。0 表示平局。
    -- 它用来区分「平局舍入」与「真实语义漂移」：
    -- 4090 复现中 144 行里唯一那次翻转的 margin 恰好为 0（A=B=0.4995）。
    margin             NUMERIC(8,6) NOT NULL,
    band               TEXT   NOT NULL,
    policy_version     TEXT   NOT NULL,
    provider_id        TEXT   NOT NULL,
    model_revision     TEXT   NOT NULL,
    backend            TEXT   NOT NULL,
    prompt_sha256      TEXT   NOT NULL,
    registry_sha256    TEXT   NOT NULL,
    input_tokens       INT    NOT NULL,
    latency_ms         BIGINT NOT NULL,
    cache_hit          BOOLEAN NOT NULL,
    degraded_reason    TEXT,
    -- 人工最终结论：把线上流量变成标注数据的唯一入口
    human_outcome      TEXT,
    human_reviewed_at  TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_decision_record_state_point
    ON decision_record (state_hash, point_id);
CREATE INDEX IF NOT EXISTS idx_decision_record_point_time
    ON decision_record (point_id, decided_at DESC);
-- 漂移分析常用：按模型版本回看被人工纠正过的判定
CREATE INDEX IF NOT EXISTS idx_decision_record_revision_reviewed
    ON decision_record (model_revision) WHERE human_outcome IS NOT NULL;
