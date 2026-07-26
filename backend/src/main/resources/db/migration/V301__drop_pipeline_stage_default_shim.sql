-- -----------------------------------------------------------------------------
-- V301 — remove the crm.pipeline_stage default-pipeline shim added by V300.
--
-- V300 added a BEFORE INSERT trigger that backfilled pipeline_id when a caller
-- omitted it, because TenantLifecycleService.seedPipeline() did exactly that and
-- no tenant could be provisioned. V300 labelled it a shim and named the real
-- fix: one insert in seedPipeline(). That fix is now in place — the method
-- creates the tenant's default pipeline and passes its id on every stage row.
--
-- The shim is dropped rather than left in as belt-and-braces on purpose. A
-- trigger that quietly invents a foreign key turns "a caller forgot pipeline_id"
-- from a loud not-null violation at the point of the mistake into a stage
-- silently attached to whichever pipeline happened to sort first. The NOT NULL
-- constraint V60 declared is the guard rail; anything that routes around it
-- weakens the schema's own statement about what a valid stage is.
-- -----------------------------------------------------------------------------

drop trigger if exists trg_pipeline_stage_default_pipeline on crm.pipeline_stage;
drop function if exists crm.pipeline_stage_default_pipeline();
