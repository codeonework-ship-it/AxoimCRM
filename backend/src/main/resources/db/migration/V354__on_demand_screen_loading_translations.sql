create temporary table seed_on_demand_i18n(
  key_path text primary key,
  description text not null,
  en text not null,
  de text not null,
  ru text not null
);

insert into seed_on_demand_i18n values
  ('ui.load.eyebrow','On-demand screen eyebrow','On-Demand Data','Daten auf Abruf','Данные по запросу'),
  ('ui.load.title','On-demand screen title','Load This Screen','Diesen Bildschirm laden','Загрузить этот экран'),
  ('ui.load.description','On-demand screen explanation',
   'The page structure is ready. Load its tenant-scoped data only when you need it, keeping navigation fast even when the workspace contains millions of records.',
   'Die Seitenstruktur ist bereit. Laden Sie mandantenbezogene Daten nur bei Bedarf, damit die Navigation auch bei Millionen Datensätzen schnell bleibt.',
   'Структура страницы готова. Загружайте данные арендатора только при необходимости, чтобы навигация оставалась быстрой даже при миллионах записей.'),
  ('ui.load.whatLoads','On-demand contract heading','What Loads','Was geladen wird','Что загружается'),
  ('ui.load.contract','On-demand contract detail',
   'The first 100 server-filtered rows, page summaries, and the active screen''s supporting data.',
   'Die ersten 100 serverseitig gefilterten Zeilen, Seitenzusammenfassungen und unterstützende Daten des aktiven Bildschirms.',
   'Первые 100 строк после серверной фильтрации, сводки страницы и вспомогательные данные активного экрана.'),
  ('ui.load.help','On-demand loading help',
   'Axiom never sends the entire million-row dataset to the browser. Search, filters and pagination remain on the server.',
   'Axiom sendet niemals den gesamten Datensatz mit Millionen Zeilen an den Browser. Suche, Filter und Seitennavigation bleiben auf dem Server.',
   'Axiom никогда не отправляет весь набор из миллионов строк в браузер. Поиск, фильтры и пагинация выполняются на сервере.'),
  ('ui.load.button','On-demand load action','Load Screen Data','Bildschirmdaten laden','Загрузить данные экрана');

insert into i18n.translation_key(key_path, description, module_code)
select key_path, description, 'SHELL' from seed_on_demand_i18n
on conflict (key_path) do update set description=excluded.description, module_code=excluded.module_code;

insert into i18n.translation(key_id, locale_code, value)
select key_row.id, language.locale_code, language.value
from seed_on_demand_i18n seed
join i18n.translation_key key_row on key_row.key_path=seed.key_path
cross join lateral (values ('en',seed.en),('de',seed.de),('ru',seed.ru)) language(locale_code,value)
on conflict (key_id,locale_code) do update set value=excluded.value, updated_at=now();

drop table seed_on_demand_i18n;
