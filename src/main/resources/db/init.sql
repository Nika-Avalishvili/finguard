-- Enable pgvector extension for the default (finguard) database
CREATE EXTENSION IF NOT EXISTS vector;

-- Create a separate test database with pgvector for integration tests
CREATE DATABASE finguard_test OWNER finguard;
\connect finguard_test
CREATE EXTENSION IF NOT EXISTS vector;
