-- Meniru role bawaan Supabase agar RLS dan pencabutan hak dapat diuji secara lokal.
-- Seperti Supabase, role ini mendapat hak default atas tabel baru; migrasi wajib mencabutnya.
CREATE ROLE anon NOLOGIN;
CREATE ROLE authenticated NOLOGIN;
GRANT USAGE ON SCHEMA public TO anon, authenticated;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO anon, authenticated;

CREATE DATABASE studyflow_test;
\connect studyflow_test
GRANT USAGE ON SCHEMA public TO anon, authenticated;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO anon, authenticated;
