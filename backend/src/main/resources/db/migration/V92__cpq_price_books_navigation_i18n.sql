-- Register the CPQ price-book workspace label in the database-backed shell
-- vocabulary. Kept separate from V91 because V91 may already be applied in
-- developer databases; applied Flyway migrations are immutable.

insert into i18n.translation_key(key_path, module_code, description) values
  ('nav.module.priceBooks', 'SHELL', 'Module: CPQ price books')
on conflict (key_path) do nothing;

insert into i18n.translation(key_id, locale_code, value)
select k.id, seed.locale_code, seed.value
from i18n.translation_key k
cross join (values
  ('en', 'Price Books'),
  ('de', 'Preislisten'),
  ('ru', 'Прайсбуки')
) as seed(locale_code, value)
where k.key_path = 'nav.module.priceBooks'
on conflict (key_id, locale_code) do update set value = excluded.value, updated_at = now();
