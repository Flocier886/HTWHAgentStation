-- AiAgentConfig constructs PgVectorStore with vectorTableName("vector_store_test").
-- The default OpenAI embedding dimension is 1536.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE IF NOT EXISTS vector_store_test (
  id uuid DEFAULT uuid_generate_v4() PRIMARY KEY,
  content text,
  metadata json,
  embedding vector(1536)
);

CREATE INDEX IF NOT EXISTS vector_store_test_embedding_idx
  ON vector_store_test USING hnsw (embedding vector_cosine_ops);
