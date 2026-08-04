from pathlib import Path

path = Path("app/src/main/java/com/amaral/driverlab/RunCoordinator.java")
text = path.read_text(encoding="utf-8")
old_assignment = """        this.executionContext = executionContext == null ? null
                : new JSONObject(executionContext.toString());
        this.listener = listener;
    }

    void start() {
"""
new_assignment = """        this.executionContext = copyExecutionContext(executionContext);
        this.listener = listener;
    }

    private static JSONObject copyExecutionContext(JSONObject source) {
        if (source == null) return null;
        try {
            return new JSONObject(source.toString());
        } catch (Exception error) {
            throw new IllegalArgumentException("campaign_context inválido", error);
        }
    }

    void start() {
"""
count = text.count(old_assignment)
if count != 1:
    raise SystemExit(f"Expected one constructor assignment, found {count}")
path.write_text(text.replace(old_assignment, new_assignment), encoding="utf-8")
print("Updated RunCoordinator execution context copy")
