-- Localized Jasper/CRM report catalogue content. Report definitions remain the
-- governed English source of truth; the UI resolves these exact values through
-- the phrase bundle, keeping grid, viewer and download metadata aligned.

create temporary table seed_report_i18n(
  code text primary key,
  label_en text, label_de text, label_ru text,
  description_en text, description_de text, description_ru text,
  question_en text, question_de text, question_ru text
);

insert into seed_report_i18n values
('tenant_summary',
 'Revenue Command Summary','Zusammenfassung der Umsatzsteuerung','Сводка управления выручкой',
 'A one-page CRM operating summary covering customers, demand and open revenue.','Einseitige CRM-Betriebsübersicht zu Kunden, Nachfrage und offenem Umsatz.','Одностраничная операционная сводка CRM по клиентам, спросу и открытой выручке.',
 'What is the current commercial position of this company?','Wie ist die aktuelle Geschäftslage dieses Unternehmens?','Каково текущее коммерческое положение компании?'),
('pipeline_snapshot',
 'Pipeline by Stage','Pipeline nach Phase','Воронка по этапам',
 'Open opportunity value and deal volume arranged in selling-stage order.','Offener Verkaufschancenwert und Geschäftsvolumen nach Vertriebsphase.','Стоимость открытых возможностей и объем сделок по этапам продаж.',
 'Where is open pipeline concentrated today?','Wo konzentriert sich die offene Pipeline heute?','На каких этапах сегодня сосредоточена открытая воронка?'),
('forecast_commitment',
 'Forecast Commitment','Prognosezusagen','Прогноз обязательств',
 'Open pipeline grouped by forecast category with weighted value.','Offene Pipeline nach Prognosekategorie mit gewichtetem Wert.','Открытая воронка по категориям прогноза со взвешенной стоимостью.',
 'How much pipeline is commit, best case, pipeline or omitted?','Wie viel Pipeline entfällt auf Zusage, Best Case, Pipeline oder Auslassung?','Какая часть воронки относится к обязательствам, лучшему сценарию, воронке или исключена?'),
('pipeline_aging_risk',
 'Pipeline Aging and Risk','Pipeline-Alterung und Risiko','Сроки и риски воронки',
 'Stage-level stale-deal and overdue-close-date exposure.','Risiken durch stagnierende Geschäfte und überfällige Abschlussdaten je Phase.','Риски зависших сделок и просроченных дат закрытия по этапам.',
 'Which stages contain revenue that is stalled or already overdue?','Welche Phasen enthalten stagnierenden oder bereits überfälligen Umsatz?','На каких этапах выручка остановилась или уже просрочена?'),
('win_loss_analysis',
 'Win and Loss Analysis','Gewinn- und Verlustanalyse','Анализ побед и проигрышей',
 'Closed outcome value, volume and average sales-cycle duration.','Wert und Volumen abgeschlossener Ergebnisse sowie durchschnittliche Vertriebszyklusdauer.','Стоимость и объем закрытых результатов и средняя длительность цикла продаж.',
 'What did we win or lose, and how long did those decisions take?','Was haben wir gewonnen oder verloren und wie lange dauerten diese Entscheidungen?','Что мы выиграли или проиграли и сколько времени заняли решения?'),
('lead_conversion_funnel',
 'Lead Conversion Funnel','Lead-Konvertierungstrichter','Воронка конверсии лидов',
 'Lead volume and converted volume by current lifecycle status.','Lead-Volumen und konvertiertes Volumen nach aktuellem Lebenszyklusstatus.','Объем лидов и конверсий по текущему статусу жизненного цикла.',
 'Where are prospects accumulating or leaving the demand funnel?','Wo sammeln sich Interessenten oder verlassen den Nachfragetrichter?','Где потенциальные клиенты накапливаются или покидают воронку спроса?'),
('lead_source_conversion',
 'Lead Source Conversion','Konvertierung nach Lead-Quelle','Конверсия по источникам лидов',
 'Demand source volume, converted leads and conversion percentage.','Volumen der Nachfragequellen, konvertierte Leads und Konversionsrate.','Объем источников спроса, конвертированные лиды и процент конверсии.',
 'Which sources create leads that actually convert?','Welche Quellen erzeugen Leads, die tatsächlich konvertieren?','Какие источники создают лиды, которые действительно конвертируются?'),
('sales_activity_productivity',
 'Sales Activity Productivity','Produktivität der Vertriebsaktivitäten','Продуктивность продаж',
 'Activity volume, completion and time invested by activity type.','Aktivitätsvolumen, Abschluss und Zeitaufwand nach Aktivitätstyp.','Объем, выполнение и затраченное время по типам активности.',
 'Is the team completing the customer work it creates?','Schließt das Team die von ihm angelegte Kundenarbeit ab?','Завершает ли команда созданную работу с клиентами?'),
('account_health_portfolio',
 'Account Health Portfolio','Portfolio der Kundengesundheit','Портфель состояния клиентов',
 'Customer count, revenue exposure and average score by health band.','Kundenanzahl, Umsatzrisiko und Durchschnittswert nach Gesundheitsstufe.','Количество клиентов, подверженная риску выручка и средний показатель по уровням состояния.',
 'How much customer value is healthy, watch-listed or at risk?','Wie viel Kundenwert ist gesund, beobachtungsbedürftig oder gefährdet?','Какая доля клиентской ценности стабильна, под наблюдением или под риском?'),
('customer_service_sla',
 'Customer Service SLA','Kundenservice-SLA','SLA клиентского сервиса',
 'Case volume and overdue response or resolution milestones by priority.','Fallvolumen und überfällige Reaktions- oder Lösungsmeilensteine nach Priorität.','Объем обращений и просроченные сроки ответа или решения по приоритетам.',
 'Where are customer commitments currently at risk of breach?','Wo sind Kundenverpflichtungen aktuell gefährdet?','Какие обязательства перед клиентами сейчас находятся под угрозой нарушения?'),
('quote_conversion_margin',
 'Quote Conversion and Margin','Angebotskonvertierung und Marge','Конверсия предложений и маржа',
 'Quote volume, commercial value and average margin by quote status.','Angebotsvolumen, Geschäftswert und Durchschnittsmarge nach Angebotsstatus.','Объем предложений, коммерческая стоимость и средняя маржа по статусам.',
 'What is the value and margin posture of issued commercial offers?','Wie sind Wert und Marge der ausgegebenen Angebote?','Каковы стоимость и маржинальность выпущенных коммерческих предложений?'),
('campaign_roi',
 'Campaign Return and Response','Kampagnenertrag und Reaktion','Отдача и отклик кампаний',
 'Campaign budget, influenced pipeline, audience response and indicative return.','Kampagnenbudget, beeinflusste Pipeline, Zielgruppenreaktion und indikativer Ertrag.','Бюджет кампании, влияние на воронку, отклик аудитории и ориентировочная отдача.',
 'Which campaigns are producing engagement and influenced pipeline?','Welche Kampagnen erzeugen Interaktion und beeinflussen die Pipeline?','Какие кампании создают вовлечение и влияют на воронку?'),
('data_quality_exceptions',
 'CRM Data Quality Exceptions','Ausnahmen der CRM-Datenqualität','Исключения качества данных CRM',
 'Actionable counts of missing ownership, contactability and process-critical fields.','Bearbeitbare Anzahlen fehlender Zuständigkeit, Erreichbarkeit und prozesskritischer Felder.','Контролируемое число пропусков владельцев, контактных данных и критичных полей процесса.',
 'Which missing CRM data can make forecasts, routing or follow-up unreliable?','Welche fehlenden CRM-Daten machen Prognosen, Routing oder Nachverfolgung unzuverlässig?','Какие отсутствующие данные CRM делают прогнозы, маршрутизацию или последующие действия ненадежными?'),
('quota_attainment',
 'Quota Attainment by Representative and Territory','Zielerreichung nach Mitarbeiter und Gebiet','Выполнение квот по сотрудникам и территориям',
 'Revenue attainment against the current governed quota version for each representative or territory.','Umsatzerreichung gegenüber der aktuellen kontrollierten Quotenversion je Mitarbeiter oder Gebiet.','Выполнение плана выручки относительно действующей квоты по сотрудникам или территориям.',
 'Who is ahead of quota, who has a gap, and which governed target was used?','Wer liegt über der Quote, wer hat eine Lücke und welches kontrollierte Ziel wurde verwendet?','Кто опережает квоту, у кого есть разрыв и какая утвержденная цель использовалась?'),
('forecast_accuracy_bias',
 'Forecast Accuracy and Directional Bias','Prognosegenauigkeit und systematische Abweichung','Точность и направленное смещение прогноза',
 'Submitted forecasts compared with closed-won actuals in the same owner and reporting period.','Eingereichte Prognosen im Vergleich zu gewonnenen Ist-Werten desselben Verantwortlichen und Berichtszeitraums.','Сопоставление прогнозов с фактическими выигранными сделками владельца за тот же период.',
 'How accurate are submitted forecasts, and do they consistently overstate or understate outcomes?','Wie genau sind Prognosen und über- oder unterschätzen sie Ergebnisse systematisch?','Насколько точны прогнозы и систематически ли они завышают или занижают результаты?'),
('stage_conversion_velocity',
 'Stage Conversion and Sales Velocity','Phasenkonvertierung und Vertriebsgeschwindigkeit','Конверсия этапов и скорость продаж',
 'Cohort-based stage entries, forward exits and elapsed selling time from append-only stage history.','Kohortenbasierte Phaseneintritte, Vorwärtsaustritte und Verkaufsdauer aus der unveränderlichen Phasenhistorie.','Входы в этапы, переходы вперед и время продаж по когортам из неизменяемой истории.',
 'Where does pipeline convert, stall or consume the most selling time?','Wo konvertiert oder stagniert die Pipeline und wo bindet sie die meiste Verkaufszeit?','Где воронка конвертируется, останавливается или требует больше всего времени?'),
('renewal_arr_bridge',
 'Renewal, Churn and ARR Movement Bridge','Verlängerungs-, Abwanderungs- und ARR-Brücke','Мост продлений, оттока и движения ARR',
 'Opening, new, churned, renewal-due and closing annual recurring revenue from governed subscriptions.','Anfangs-, Neu-, Abwanderungs-, Verlängerungs- und End-ARR aus kontrollierten Abonnements.','Начальный, новый, потерянный, подлежащий продлению и конечный ARR по управляемым подпискам.',
 'What changed recurring revenue, and how much ARR is approaching renewal?','Was hat den wiederkehrenden Umsatz verändert und wie viel ARR steht zur Verlängerung an?','Что изменило регулярную выручку и какой объем ARR приближается к продлению?'),
('pipeline_movement_waterfall',
 'Pipeline Movement Waterfall','Wasserfall der Pipeline-Bewegung','Водопад движения воронки',
 'An exactly reconciling pipeline comparison using append-only opportunity state history.','Exakt abstimmbare Pipeline-Gegenüberstellung aus der unveränderlichen Verkaufschancenhistorie.','Точно сверяемое сравнение воронки на основе неизменяемой истории состояний возможностей.',
 'What was added, grown, shrunk, won, lost or removed from pipeline during the period?','Was wurde im Zeitraum hinzugefügt, erhöht, reduziert, gewonnen, verloren oder entfernt?','Что было добавлено, увеличено, сокращено, выиграно, проиграно или удалено за период?'),
('account_whitespace',
 'Account Whitespace and Cross-Sell Opportunity','Kundenpotenzial und Cross-Selling-Chancen','Незаполненный потенциал и кросс-продажи',
 'Active catalogue products absent from each account current subscriptions and open opportunity lines.','Aktive Katalogprodukte, die in aktuellen Abonnements und offenen Verkaufschancen eines Kunden fehlen.','Активные продукты каталога, отсутствующие в текущих подписках и открытых возможностях клиента.',
 'Which active products are not yet represented in each customer relationship?','Welche aktiven Produkte fehlen noch in der jeweiligen Kundenbeziehung?','Какие активные продукты еще не представлены в отношениях с каждым клиентом?'),
('customer_360_brief',
 'Customer 360 Executive Brief','Customer-360-Managementübersicht','Руководящий обзор клиента 360',
 'One-row-per-account executive view of health, ARR, pipeline, service demand, contacts and renewal timing.','Managementansicht je Kunde zu Gesundheit, ARR, Pipeline, Servicebedarf, Kontakten und Verlängerungsterminen.','Руководящий обзор по каждому клиенту: состояние, ARR, воронка, сервис, контакты и сроки продления.',
 'What is the complete commercial and service posture of each customer account?','Wie ist die vollständige Geschäfts- und Servicelage jedes Kunden?','Каково полное коммерческое и сервисное состояние каждого клиента?'),
('discount_approval_governance',
 'Discount Leakage and Approval Governance','Rabattverlust und Genehmigungssteuerung','Потери от скидок и контроль согласований',
 'Active quote discount, margin and approval posture with exceptions surfaced for review.','Aktuelle Rabatt-, Margen- und Genehmigungslage von Angeboten mit prüfbaren Ausnahmen.','Состояние скидок, маржи и согласований по предложениям с исключениями для проверки.',
 'Where are discounts reducing commercial value without adequate approval or margin protection?','Wo reduzieren Rabatte den Geschäftswert ohne ausreichende Genehmigung oder Margenschutz?','Где скидки снижают коммерческую ценность без надлежащего согласования или защиты маржи?');

create temporary table expanded_report_i18n as
select 'report.definition.' || code || '.label' key_path, 'Report title: ' || code description,
       label_en en, label_de de, label_ru ru from seed_report_i18n
union all
select 'report.definition.' || code || '.description', 'Report description: ' || code,
       description_en, description_de, description_ru from seed_report_i18n
union all
select 'report.definition.' || code || '.question', 'Report business question: ' || code,
       question_en, question_de, question_ru from seed_report_i18n;

insert into i18n.translation_key(key_path, description, module_code)
select key_path, description, 'REPORTING'
from expanded_report_i18n
on conflict (key_path) do update
set description = excluded.description,
    module_code = excluded.module_code;

insert into i18n.translation(key_id, locale_code, value)
select k.id, language.locale_code, language.value
from expanded_report_i18n seed
join i18n.translation_key k on k.key_path = seed.key_path
cross join lateral (values ('en', seed.en), ('de', seed.de), ('ru', seed.ru)) language(locale_code, value)
on conflict (key_id, locale_code) do update
set value = excluded.value,
    updated_at = now();

drop table expanded_report_i18n;
drop table seed_report_i18n;
