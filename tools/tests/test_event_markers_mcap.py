import json
import os
import tempfile
import unittest

from mcap.reader import make_reader

from tools.convert_session_to_mcap import convert_session_to_mcap, parse_timeline_events


class EventMarkersMcapTest(unittest.TestCase):
    def test_parse_timeline_events_extracts_all_bookmark_types(self):
        with tempfile.TemporaryDirectory() as tmp:
            timeline = os.path.join(tmp, "session_timeline.csv")
            with open(timeline, "w", encoding="utf-8") as f:
                f.write("elapsed_realtime_ns,sensor,event,sequence_id,relative_path,summary\n")
                f.write("1000000000,CAMERA,FRAME,1,camera/camera_video.mp4,\n")
                f.write("1500000000,USER,EVENT_MARKER,1,session_timeline.csv,type=EVENT\n")
                f.write("2500000000,USER,EVENT_MARKER,2,session_timeline.csv,type=CUT_IN;note=bus merged left\n")
                f.write("3500000000,USER,EVENT_MARKER,3,session_timeline.csv,type=BRAKE\n")

            events = parse_timeline_events(timeline)
            self.assertEqual(len(events), 3)
            self.assertEqual(events[0]["event_type"], "EVENT")
            self.assertEqual(events[0]["sequence_id"], 1)
            self.assertEqual(events[1]["event_type"], "CUT_IN")
            self.assertEqual(events[1]["note"], "bus merged left")
            self.assertEqual(events[2]["event_type"], "BRAKE")

    def test_mcap_export_writes_events_channel_and_diagnostics_mirror(self):
        with tempfile.TemporaryDirectory() as tmp:
            session_dir = os.path.join(tmp, "session_20261125_120000")
            os.makedirs(session_dir, exist_ok=True)

            meta = {
                "sessionId": "session_20261125_120000",
                "startTimeWallMs": 1700000000000,
                "startTimeMonotonicNs": 1000000000,
                "bookmarksCount": 2,
                "sessionTags": {"vehicleId": "Pulsar-01", "mountType": "Handlebar", "lightingCondition": "Day"}
            }
            with open(os.path.join(session_dir, "session_metadata.json"), "w", encoding="utf-8") as f:
                json.dump(meta, f)

            with open(os.path.join(session_dir, "session_timeline.csv"), "w", encoding="utf-8") as f:
                f.write("elapsed_realtime_ns,sensor,event,sequence_id,relative_path,summary\n")
                f.write("1500000000,USER,EVENT_MARKER,1,session_timeline.csv,type=CUT_IN\n")
                f.write("2500000000,USER,EVENT_MARKER,2,session_timeline.csv,type=BUMP;note=speedbreaker\n")

            out_mcap = os.path.join(tmp, "out.mcap")
            convert_session_to_mcap(
                session_dir=session_dir,
                output_path=out_mcap,
                include_video=False,
                skip_can=True,
            )
            self.assertTrue(os.path.isfile(out_mcap))

            events_msgs = []
            diag_msgs = []
            with open(out_mcap, "rb") as f:
                reader = make_reader(f)
                for schema, channel, message in reader.iter_messages():
                    if channel.topic == "/events":
                        events_msgs.append((schema.name, json.loads(message.data.decode("utf-8"))))
                    elif channel.topic == "/diagnostics/logs":
                        diag_msgs.append(message)

            self.assertEqual(len(events_msgs), 2)
            self.assertEqual(events_msgs[0][0], "roadsense.EventMarker")
            self.assertEqual(events_msgs[0][1]["event_type"], "CUT_IN")
            self.assertEqual(events_msgs[1][1]["event_type"], "BUMP")
            self.assertEqual(events_msgs[1][1]["note"], "speedbreaker")
            self.assertEqual(len(diag_msgs), 2)


if __name__ == "__main__":
    unittest.main()
