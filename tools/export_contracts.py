"""Export the actual application's OpenAPI, rather than a parallel hand-written spec."""

from pathlib import Path
import json
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "services" / "api"))

from carplay.main import create_app
from carplay.settings import Settings
from carplay.contracts import ActionPayload, EmptyPayload, PausePayload, PlaybackPayload, SemanticCandidate


def main() -> None:
    app = create_app(Settings(db_path=":memory:", demo_tokens={}))
    destination = ROOT / "packages" / "contracts" / "openapi.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(app.openapi(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(destination.relative_to(ROOT))
    # Command.payload has runtime validation selected by type. Export those exact
    # validators as separate schemas so clients need not infer an unconstrained dict.
    schemas = {
        "action.submit": ActionPayload,
        "playback.completed": PlaybackPayload,
        "session.pause": PausePayload,
        "session.resume": EmptyPayload,
        "session.end": EmptyPayload,
        "semantic-candidate": SemanticCandidate,
    }
    for name, model in schemas.items():
        target = destination.parent / f"{name}.schema.json"
        target.write_text(json.dumps(model.model_json_schema(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{len(schemas)} typed payload schemas")


if __name__ == "__main__":
    main()
