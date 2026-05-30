-- Operator-managed TIDAL public web token (x-tidal-token) used by EMS discovery to fetch
-- TIDAL playlist tracks. Single row; entered/updated from the scheduling admin page.
create table tidal_web_token (
    id smallint primary key,
    token text not null,
    updated_by varchar(100),
    updated_at timestamptz not null
);
