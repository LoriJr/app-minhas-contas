create table recurring_bill (
    id uuid primary key,
    owner_id uuid not null references app_user(id),
    title varchar(120) not null,
    category varchar(50) not null,
    source varchar(200),
    amount numeric(14,2) not null check(amount > 0),
    due_day integer not null check(due_day between 1 and 31),
    reminder_days integer not null check(reminder_days between 0 and 30),
    start_month date not null,
    version bigint not null default 0
);
create index recurring_owner on recurring_bill(owner_id);
alter table bill add column recurring_id uuid references recurring_bill(id);
alter table bill add column billing_month date;

-- Imported copies form one series. Unrelated manually entered bills remain separate.
-- Track visited IDs to safely terminate any malformed import lineage.
create temporary table recurrence_mapping on commit drop as
with recursive ancestry as (
    select id as bill_id, id as ancestor_id, owner_id, import_source_id as parent_id,
           array[id] as visited, 0 as depth
    from bill
    union all
    select a.bill_id, parent.id, a.owner_id, parent.import_source_id,
           a.visited || parent.id, a.depth + 1
    from ancestry a join bill parent on parent.id = a.parent_id and parent.owner_id = a.owner_id
    where not parent.id = any(a.visited)
)
select distinct on (bill_id) bill_id, ancestor_id as series_id
from ancestry order by bill_id, depth desc;

insert into recurring_bill(id, owner_id, title, category, source, amount, due_day, reminder_days, start_month)
select root.id, root.owner_id, latest.title, latest.category, latest.source, latest.amount,
       extract(day from root.due)::integer, latest.reminder_days, first_month.start_month
from (select distinct series_id from recurrence_mapping) m
join bill root on root.id = m.series_id
cross join lateral (
    select b.* from bill b join recurrence_mapping r on r.bill_id = b.id
    where r.series_id = m.series_id order by b.due desc, b.id limit 1
) latest
cross join lateral (
    select min(date_trunc('month', b.due)::date) as start_month
    from bill b join recurrence_mapping r on r.bill_id = b.id where r.series_id = m.series_id
) first_month;

update bill b set recurring_id = m.series_id, billing_month = date_trunc('month', b.due)::date
from recurrence_mapping m where m.bill_id = b.id;
-- An imported occurrence can have been moved into a month that already has another occurrence.
-- Keep every historical row by making collisions independent recurrences instead of deleting data.
create temporary table recurrence_collisions on commit drop as
select id from (
    select id, row_number() over(partition by recurring_id,billing_month order by (id = recurring_id) desc, paid desc,id) as n
    from bill
) ranked where n > 1;
insert into recurring_bill(id, owner_id, title, category, source, amount, due_day, reminder_days, start_month)
select b.id,b.owner_id,b.title,b.category,b.source,b.amount,extract(day from b.due)::integer,b.reminder_days,b.billing_month
from bill b join recurrence_collisions c on c.id = b.id;
update bill b set recurring_id = b.id from recurrence_collisions c where c.id = b.id;

alter table bill alter column recurring_id set not null;
alter table bill alter column billing_month set not null;
create unique index bill_series_month on bill(recurring_id,billing_month);
drop index bill_import_once;
alter table bill drop column import_source_id, drop column import_month,
                 drop column payment_type, drop column payment_code;
