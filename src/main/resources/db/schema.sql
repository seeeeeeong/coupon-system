CREATE TABLE IF NOT EXISTS coupon_template (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    name           VARCHAR(100) NOT NULL,
    discount_amount INT NOT NULL,
    total_quantity  INT NOT NULL,
    event_start_at  DATETIME NOT NULL,
    event_end_at    DATETIME NOT NULL,
    created_at      DATETIME NOT NULL
);

CREATE TABLE IF NOT EXISTS coupon_outbox (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_type  VARCHAR(50)  NOT NULL,
    payload     JSON         NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'INIT',
    retry_count INT          NOT NULL DEFAULT 0,
    claim_token VARCHAR(36)  NULL,     -- relay 인스턴스 세대 식별자. PUBLISHING 상태에서만 값 존재
    created_at  DATETIME     NOT NULL,
    updated_at  DATETIME     NOT NULL,
    INDEX idx_status_id (status, id)
);

CREATE TABLE IF NOT EXISTS coupons (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id            BIGINT   NOT NULL,
    coupon_template_id BIGINT   NOT NULL,
    issued_at          DATETIME NOT NULL,
    UNIQUE KEY uq_user_coupon (user_id, coupon_template_id)
);
