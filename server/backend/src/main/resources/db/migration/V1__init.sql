-- =============================================================================
-- V1__init.sql - Schema PostgreSQL của hệ thống MQTT Smart Home (đặc tả mục 8).
-- Khớp 1-1 với entity trong com.example.smarthome.entity (spring.jpa.hibernate.ddl-auto=validate).
-- =============================================================================

CREATE TABLE users (
  id            BIGSERIAL PRIMARY KEY,
  username      VARCHAR(50)  UNIQUE NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  role          VARCHAR(20)  NOT NULL DEFAULT 'ADMIN',
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE devices (
  id         BIGSERIAL PRIMARY KEY,
  device_id  VARCHAR(50)  UNIQUE NOT NULL,                    -- node1, node2
  name       VARCHAR(100) NOT NULL,
  type       VARCHAR(20)  NOT NULL,                           -- sensor | actuator
  reg_token  VARCHAR(64)  NOT NULL,                           -- token cấp khi đăng ký thiết bị
  reg_status VARCHAR(20)  NOT NULL DEFAULT 'PENDING',         -- PENDING | ACTIVE
  online     BOOLEAN      NOT NULL DEFAULT FALSE,
  fw_version VARCHAR(20),
  last_seen  TIMESTAMPTZ,
  created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE telemetry (
  id          BIGSERIAL PRIMARY KEY,
  device_id   VARCHAR(50) NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
  temp        REAL,
  hum         REAL,
  relay1      BOOLEAN,
  relay2      BOOLEAN,
  recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_tele_dev_time ON telemetry(device_id, recorded_at DESC);

CREATE TABLE commands (
  id         BIGSERIAL PRIMARY KEY,
  cmd_id     VARCHAR(40) UNIQUE NOT NULL,
  device_id  VARCHAR(50) NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
  target     VARCHAR(20) NOT NULL,                            -- led1 | led2 | relay1 | relay2
  value      VARCHAR(10) NOT NULL,                            -- ON | OFF
  status     VARCHAR(20) NOT NULL DEFAULT 'PENDING',          -- PENDING | DONE | ERROR | TIMEOUT
  created_by VARCHAR(50),
  sent_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  acked_at   TIMESTAMPTZ
);

-- Ghi chú: user admin (mật khẩu BCrypt) được seed từ biến môi trường khi backend
-- khởi động ở Phase 3 (không đặt hash cố định trong migration để tránh lộ mật khẩu).
