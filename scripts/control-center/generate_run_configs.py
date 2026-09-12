import os
from pathlib import Path

SERVICES = [
    ("App - Inventory Service", "inventory"),
    ("App - Product Service", "product"),
    ("App - Customer Service", "customer"),
    ("App - Promotion Service", "promotion"),
    ("App - Delivery Service", "delivery"),
    ("App - Payment Service", "payment"),
    ("App - Order Service", "order"),
    ("App - Notification Service", "notification"),
    ("App - Billing Service", "billing"),
    ("App - Wishlist Service", "wishlist"),
    ("App - Review Service", "review"),
    ("App - Recommendation Service", "recommendation"),
    ("App - Chat Service", "chat"),
    ("App - Returns Service", "returns"),
    ("App - API Gateway", "gateway")
]

POWERSHELL_PATH = "C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe"

def main():
    root = Path(__file__).resolve().parents[2]
    win_root = "C:/Dysk D/stuff/Mini Order Platform"
    run_dir = root / ".run"
    run_dir.mkdir(exist_ok=True)

    # 1. Services (using Script text mode to avoid IntelliJ path escaping with spaces)
    for name, alias in SERVICES:
        safe_name = name.replace(" ", "_").replace("-", "_") + ".run.xml"
        cmd = f"&amp; '.\\scripts\\control-center\\ide-run.ps1' -Service {alias} -Profile dev"
        xml = f"""<component name="ProjectRunConfigurationManager">
  <configuration default="false" name="{name}" type="ShConfigurationType">
    <option name="SCRIPT_TEXT" value="{cmd}" />
    <option name="INDEPENDENT_SCRIPT_PATH" value="true" />
    <option name="SCRIPT_PATH" value="" />
    <option name="SCRIPT_OPTIONS" value="" />
    <option name="INDEPENDENT_SCRIPT_WORKING_DIRECTORY" value="true" />
    <option name="SCRIPT_WORKING_DIRECTORY" value="{win_root}" />
    <option name="INDEPENDENT_INTERPRETER_PATH" value="true" />
    <option name="INTERPRETER_PATH" value="{POWERSHELL_PATH}" />
    <option name="INTERPRETER_OPTIONS" value="-NoProfile -ExecutionPolicy Bypass -Command" />
    <option name="EXECUTE_IN_TERMINAL" value="true" />
    <option name="EXECUTE_SCRIPT_FILE" value="false" />
    <envs />
    <method v="2" />
  </configuration>
</component>
"""
        (run_dir / safe_name).write_text(xml, encoding="utf-8")

    # 2. Infra Up
    infra_cmd = "&amp; '.\\scripts\\control-center\\start.ps1' -CurrentWindow infra-up"
    infra_xml = f"""<component name="ProjectRunConfigurationManager">
  <configuration default="false" name="Infra - Up (Databases, Redis, Mailpit)" type="ShConfigurationType">
    <option name="SCRIPT_TEXT" value="{infra_cmd}" />
    <option name="INDEPENDENT_SCRIPT_PATH" value="true" />
    <option name="SCRIPT_PATH" value="" />
    <option name="SCRIPT_OPTIONS" value="" />
    <option name="INDEPENDENT_SCRIPT_WORKING_DIRECTORY" value="true" />
    <option name="SCRIPT_WORKING_DIRECTORY" value="{win_root}" />
    <option name="INDEPENDENT_INTERPRETER_PATH" value="true" />
    <option name="INTERPRETER_PATH" value="{POWERSHELL_PATH}" />
    <option name="INTERPRETER_OPTIONS" value="-NoProfile -ExecutionPolicy Bypass -Command" />
    <option name="EXECUTE_IN_TERMINAL" value="true" />
    <option name="EXECUTE_SCRIPT_FILE" value="false" />
    <envs />
    <method v="2" />
  </configuration>
</component>
"""
    (run_dir / "Infra___Up.run.xml").write_text(infra_xml, encoding="utf-8")

    # 3. Frontend Up
    frontend_cmd = "&amp; '.\\scripts\\control-center\\start.ps1' -CurrentWindow frontend-up"
    frontend_xml = f"""<component name="ProjectRunConfigurationManager">
  <configuration default="false" name="Frontend - Up (Store :18070)" type="ShConfigurationType">
    <option name="SCRIPT_TEXT" value="{frontend_cmd}" />
    <option name="INDEPENDENT_SCRIPT_PATH" value="true" />
    <option name="SCRIPT_PATH" value="" />
    <option name="SCRIPT_OPTIONS" value="" />
    <option name="INDEPENDENT_SCRIPT_WORKING_DIRECTORY" value="true" />
    <option name="SCRIPT_WORKING_DIRECTORY" value="{win_root}" />
    <option name="INDEPENDENT_INTERPRETER_PATH" value="true" />
    <option name="INTERPRETER_PATH" value="{POWERSHELL_PATH}" />
    <option name="INTERPRETER_OPTIONS" value="-NoProfile -ExecutionPolicy Bypass -Command" />
    <option name="EXECUTE_IN_TERMINAL" value="true" />
    <option name="EXECUTE_SCRIPT_FILE" value="false" />
    <envs />
    <method v="2" />
  </configuration>
</component>
"""
    (run_dir / "Frontend___Up.run.xml").write_text(frontend_xml, encoding="utf-8")

    # 4. Control Center Web
    cc_cmd = "&amp; '.\\scripts\\start.ps1'"
    cc_xml = f"""<component name="ProjectRunConfigurationManager">
  <configuration default="false" name="Control Center - Up (Web :18100)" type="ShConfigurationType">
    <option name="SCRIPT_TEXT" value="{cc_cmd}" />
    <option name="INDEPENDENT_SCRIPT_PATH" value="true" />
    <option name="SCRIPT_PATH" value="" />
    <option name="SCRIPT_OPTIONS" value="" />
    <option name="INDEPENDENT_SCRIPT_WORKING_DIRECTORY" value="true" />
    <option name="SCRIPT_WORKING_DIRECTORY" value="{win_root}" />
    <option name="INDEPENDENT_INTERPRETER_PATH" value="true" />
    <option name="INTERPRETER_PATH" value="{POWERSHELL_PATH}" />
    <option name="INTERPRETER_OPTIONS" value="-NoProfile -ExecutionPolicy Bypass -Command" />
    <option name="EXECUTE_IN_TERMINAL" value="true" />
    <option name="EXECUTE_SCRIPT_FILE" value="false" />
    <envs />
    <method v="2" />
  </configuration>
</component>
"""
    (run_dir / "Control_Center___Up.run.xml").write_text(cc_xml, encoding="utf-8")

    # 5. Compound: ALL SERVICES (Frontend + 15 Microservices)
    to_runs = "\n".join([f'    <toRun name="{name}" type="ShConfigurationType" />' for name, _ in SERVICES])
    to_runs_services = '    <toRun name="Frontend - Up (Store :18070)" type="ShConfigurationType" />\n' + to_runs
    compound_xml = f"""<component name="ProjectRunConfigurationManager">
  <configuration default="false" name="MiniTech - ALL SERVICES (DEV)" type="CompoundRunConfigurationType">
{to_runs_services}
    <method v="2" />
  </configuration>
</component>
"""
    (run_dir / "MiniTech___ALL_SERVICES__DEV_.run.xml").write_text(compound_xml, encoding="utf-8")

    # 6. Compound: COMPLETE STACK (Infra + Frontend + 15 Microservices)
    to_runs_complete = '    <toRun name="Infra - Up (Databases, Redis, Mailpit)" type="ShConfigurationType" />\n' + to_runs_services
    complete_xml = f"""<component name="ProjectRunConfigurationManager">
  <configuration default="false" name="MiniTech - COMPLETE STACK (Infra + Services)" type="CompoundRunConfigurationType">
{to_runs_complete}
    <method v="2" />
  </configuration>
</component>
"""
    (run_dir / "MiniTech___COMPLETE_STACK.run.xml").write_text(complete_xml, encoding="utf-8")
    print(f"Successfully generated all run configurations in {run_dir}")

if __name__ == "__main__":
    main()
