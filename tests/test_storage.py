import sqlite3
from pathlib import Path
import tempfile
import unittest

from carplay.storage import Store


class StorageTests(unittest.TestCase):
    def test_state_event_and_receipt_rollback_together(self):
        store = Store(":memory:")
        try:
            with self.assertRaises(RuntimeError):
                with store.transaction() as db:
                    db.execute("INSERT INTO sessions VALUES ('s','family','{}')")
                    db.execute("INSERT INTO session_events VALUES ('s',1,'action.applied','{}')")
                    db.execute("INSERT INTO command_receipts VALUES ('s','cmd','fingerprint','APPLIED','{}')")
                    raise RuntimeError("simulated failure before commit")
            with store.transaction() as db:
                for table in ["sessions", "session_events", "command_receipts"]:
                    self.assertEqual(db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0], 0)
        finally:
            store.close()

    def test_database_constraints_reject_duplicate_event_and_receipt(self):
        store = Store(":memory:")
        try:
            with store.transaction() as db:
                db.execute("INSERT INTO sessions VALUES ('s','family','{}')")
                db.execute("INSERT INTO session_events VALUES ('s',1,'action.applied','{}')")
                db.execute("INSERT INTO command_receipts VALUES ('s','cmd','fingerprint','APPLIED','{}')")
            for sql in ["INSERT INTO session_events VALUES ('s',1,'other','{}')",
                        "INSERT INTO command_receipts VALUES ('s','cmd','other','APPLIED','{}')"]:
                with self.assertRaises(sqlite3.IntegrityError):
                    with store.transaction() as db:
                        db.execute(sql)
        finally:
            store.close()

    def test_committed_snapshot_survives_connection_recreation(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "game.db"
            store = Store(path)
            with store.transaction() as db:
                db.execute("INSERT INTO sessions VALUES ('s','family','{\"status\":\"PAUSED\"}')")
            store.close()
            reopened = Store(path)
            try:
                with reopened.transaction() as db:
                    self.assertEqual(db.execute("SELECT snapshot_json FROM sessions").fetchone()[0], '{"status":"PAUSED"}')
            finally:
                reopened.close()


if __name__ == "__main__":
    unittest.main()
