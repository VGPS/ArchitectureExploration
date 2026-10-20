-- ============================================================
-- ArchitectureExploration — Prior Auth database schema
-- Run via: ./run.sh com.wgblackmon.db.SchemaMain
-- Idempotent: safe to run multiple times (IF NOT EXISTS throughout)
-- ============================================================

-- pgvector extension — required before any vector column can be created
CREATE EXTENSION IF NOT EXISTS vector;

-- ------------------------------------------------------------
-- Patients (populated by SyntheaIngestMain)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS patients (
    patient_id   VARCHAR(64)  PRIMARY KEY,
    first_name   VARCHAR(100),
    last_name    VARCHAR(100),
    birth_date   DATE,
    gender       VARCHAR(10),
    weight_kg    DECIMAL(6,2),
    height_cm    DECIMAL(6,2),
    bmi          DECIMAL(5,2)   -- calculated and stored during ingestion
);

-- ICD-10 diagnoses per patient
CREATE TABLE IF NOT EXISTS patient_conditions (
    id           SERIAL       PRIMARY KEY,
    patient_id   VARCHAR(64)  NOT NULL REFERENCES patients(patient_id) ON DELETE CASCADE,
    icd10_code   VARCHAR(20)  NOT NULL,
    description  TEXT,
    onset_date   DATE,
    status       VARCHAR(20) DEFAULT 'active'
);

-- Medications per patient
CREATE TABLE IF NOT EXISTS patient_medications (
    id              SERIAL       PRIMARY KEY,
    patient_id      VARCHAR(64)  NOT NULL REFERENCES patients(patient_id) ON DELETE CASCADE,
    medication_name TEXT         NOT NULL,
    rxnorm_code     VARCHAR(20),
    dosage          VARCHAR(100),
    frequency       VARCHAR(50),
    status          VARCHAR(20)  DEFAULT 'active'
);

-- Lab results and vitals per patient (LOINC-coded)
CREATE TABLE IF NOT EXISTS patient_observations (
    id             SERIAL       PRIMARY KEY,
    patient_id     VARCHAR(64)  NOT NULL REFERENCES patients(patient_id) ON DELETE CASCADE,
    loinc_code     VARCHAR(20),
    description    VARCHAR(200),
    value          VARCHAR(100),
    unit           VARCHAR(50),
    observed_date  DATE
);

-- ------------------------------------------------------------
-- Coverage policy chunks (populated by LcdIngestMain)
-- Each row is one paragraph/section from a CMS LCD or payer policy.
-- The embedding column holds the nomic-embed-text vector (768 dims).
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS coverage_policy_chunks (
    id              SERIAL        PRIMARY KEY,
    source_document VARCHAR(200)  NOT NULL,  -- e.g. "CMS LCD L38467"
    source_url      TEXT,
    section_title   VARCHAR(200),
    chunk_text      TEXT          NOT NULL,
    chunk_index     INTEGER,                 -- position within the source document
    embedding       vector(768),             -- nomic-embed-text, 768 dimensions
    created_at      TIMESTAMP     DEFAULT NOW()
);

-- ------------------------------------------------------------
-- Indexes
-- ------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_patient_conditions_patient
    ON patient_conditions(patient_id);

CREATE INDEX IF NOT EXISTS idx_patient_conditions_icd10
    ON patient_conditions(icd10_code);

CREATE INDEX IF NOT EXISTS idx_patient_medications_patient
    ON patient_medications(patient_id);

CREATE INDEX IF NOT EXISTS idx_patient_observations_patient
    ON patient_observations(patient_id);

CREATE INDEX IF NOT EXISTS idx_patient_observations_loinc
    ON patient_observations(loinc_code);

-- pgvector HNSW index for fast cosine similarity search.
-- HNSW builds incrementally so it works on empty tables and on growing datasets.
-- (If this fails, your pgvector version is < 0.5.0 — use IVFFlat instead.)
CREATE INDEX IF NOT EXISTS idx_coverage_policy_embedding
    ON coverage_policy_chunks USING hnsw (embedding vector_cosine_ops);
