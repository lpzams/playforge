CREATE TABLE projects (
    id          VARCHAR(32)  PRIMARY KEY,
    title       VARCHAR(200) NOT NULL,
    prompt      CLOB         NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE runs (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id    VARCHAR(32) NOT NULL REFERENCES projects(id),
    instruction   CLOB        NOT NULL,
    status        VARCHAR(16) NOT NULL,
    error         CLOB,
    base_commit   VARCHAR(64),
    result_commit VARCHAR(64),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at    TIMESTAMP WITH TIME ZONE,
    finished_at   TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_runs_project ON runs(project_id);
CREATE INDEX idx_runs_status ON runs(status);

CREATE TABLE steps (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id       BIGINT      NOT NULL REFERENCES runs(id),
    type         VARCHAR(16) NOT NULL,
    round        INT         NOT NULL,
    step_trigger VARCHAR(16) NOT NULL,
    status       VARCHAR(16) NOT NULL,
    ok           BOOLEAN,
    output_json  CLOB,
    error        CLOB,
    started_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at  TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_steps_run ON steps(run_id);

CREATE TABLE run_events (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id       BIGINT      NOT NULL REFERENCES runs(id),
    seq          BIGINT      NOT NULL,
    ts           TIMESTAMP WITH TIME ZONE NOT NULL,
    type         VARCHAR(32) NOT NULL,
    source       VARCHAR(32) NOT NULL,
    payload_json CLOB        NOT NULL,
    CONSTRAINT uk_run_events_seq UNIQUE (run_id, seq)
);
