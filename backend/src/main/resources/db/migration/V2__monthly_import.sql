-- Keep provenance even when the source is deleted; IDs are never reused.
alter table bill add column import_source_id uuid;
alter table bill add column import_month date;
create unique index bill_import_once on bill(owner_id, import_source_id, import_month);
