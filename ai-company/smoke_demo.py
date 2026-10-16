"""End-to-end *simulation* of the six-role Hermes AI Company workflow.

No language models, Telegram, Hermes Commander, GitHub writes or deploys are
invoked. Run from the repository root: python ai-company/smoke_demo.py
"""
from __future__ import annotations

import json
import tempfile
from pathlib import Path

from company import Company, WorkflowError


def run_demo() -> dict:
    events = []
    with tempfile.TemporaryDirectory(prefix="hermes-company-smoke-") as temp:
        company = Company(str(Path(temp) / "demo.sqlite3"))
        try:
            project_id = company.create_project(
                "TEMPLO — Demo de cotizaciones",
                "Crear un formulario de cotización y una API de ejemplo, sin datos reales.",
            )
            events.append("Director: creó el proyecto a partir del encargo simulado.")

            tasks = {
                "frontend": company.add_task(project_id, "Interfaz de cotización", "frontend",
                                             "Formulario validado y accesible"),
                "backend": company.add_task(project_id, "API de cotización", "backend",
                                            "Calcular total y rechazar entradas inválidas"),
                "integrations": company.add_task(project_id, "Pruebas de integración", "integrations",
                                                 "Comprobar formulario y API juntos"),
            }
            events.append("Producto y arquitectura: definieron tres tareas independientes.")

            # A proposal must not be possible while tasks are still unreviewed.
            preview_blocked = False
            try:
                company.propose_preview(project_id, "a" * 40,
                                        "https://example.invalid/preview")
            except WorkflowError:
                preview_blocked = True
            assert preview_blocked, "Unreviewed tasks must block preview"

            for role, task_id in tasks.items():
                company.start_task(task_id, role)
                company.submit_task(task_id, role,
                                    f"DEMO: resultados simulados de {role}; no se ejecutó código real")
                if role == "backend":
                    company.review_task(task_id, "architect", False,
                                        "DEMO: falta validación de entradas")
                    events.append("Arquitecto: rechazó la primera entrega del backend.")
                    company.start_task(task_id, role)
                    company.submit_task(task_id, role,
                                        "DEMO: segunda entrega con validación simulada")
                company.review_task(task_id, "product", True,
                                    "DEMO: requisitos funcionales simulados conformes")
                company.review_task(task_id, "architect", True,
                                    "DEMO: revisión técnica simulada conforme")

            state = company.status(project_id)
            assert all(task["state"] == "accepted" for task in state["tasks"])
            events.append("Tres tareas aceptadas por ambos supervisores.")

            # The URL is deliberately NOT a real preview.
            sha = "a" * 40
            approval = company.propose_preview(project_id, sha,
                                               "https://example.invalid/preview")
            unauthorized_blocked = False
            try:
                company.approve_preview(approval, sha, "director")
            except WorkflowError:
                unauthorized_blocked = True
            assert unauthorized_blocked, "Director must not approve production"

            # This is an internal mock only. Real Telegram authentication
            # is not implemented and this does NOT trigger deployment.
            company.approve_preview(approval, sha, "authenticated_owner")
            final = company.status(project_id)
            assert final["approvals"][0]["state"] == "approved"
            assert sum(t["attempts"] for t in final["tasks"]) == 1
            events.append("La aprobación simulada quedó ligada al SHA; no hubo despliegue.")

            return {
                "kind": "simulation_only",
                "project": "TEMPLO — Demo de cotizaciones",
                "tasks_total": len(final["tasks"]),
                "tasks_accepted": sum(t["state"] == "accepted" for t in final["tasks"]),
                "revisions_requested": sum(t["attempts"] for t in final["tasks"]),
                "dual_reviews": True,
                "preview_before_review_blocked": preview_blocked,
                "director_approval_blocked": unauthorized_blocked,
                "mock_approval_recorded": final["approvals"][0]["state"] == "approved",
                "models_connected": False,
                "telegram_connected": False,
                "hermes_commander_connected": False,
                "real_preview_created": False,
                "production_deployed": False,
                "events": events,
            }
        finally:
            company.close()


if __name__ == "__main__":
    print(json.dumps(run_demo(), ensure_ascii=False, indent=2))
