create unique index if not exists idx_application_error_log_fingerprint_unique
    on application_error_log (fingerprint);
