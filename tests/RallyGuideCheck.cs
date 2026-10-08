using System;
using System.Collections.Generic;
using System.IO;

class RallyGuideCheck
{
    static void Expect(bool condition, string message) { if (!condition) throw new Exception(message); }
    static float[][] Curve(float radius, int sign)
    {
        var p = new List<float[]>();
        for (int x = -200; x <= 0; x += 5) p.Add(new[] { (float)x, 0f });
        for (int i = 1; i <= 40; i++)
        {
            double angle = i*Math.PI/80;
            p.Add(new[] { radius*(float)Math.Sin(angle), sign*radius*(1-(float)Math.Cos(angle)) });
        }
        for (int z = 5; z <= 200; z += 5) p.Add(new[] { radius, sign*(radius+z) });
        return p.ToArray();
    }
    static int Main(string[] args)
    {
        foreach (int sign in new[] { -1, 1 })
        {
            var guide = new RallyGuide(Curve(80, sign), false);
            Expect(guide.Cues.Count == 1, "Single turn, no false extra cues");
            Expect(guide.Cues[0].Direction == sign, "Left/right world-coordinate orientation");
            Expect(guide.Cues[0].Grade == 3, "80m radius maps to grade 3");
            float[] gaps; var first = guide.Upcoming(0, out gaps);
            Expect(first.Length == 1 && gaps[0] > 150, "Ahead distance in metres");
            Expect(guide.Upcoming(1, out gaps).Length == 0, "Open routes do not wrap to start");
        }
        var straight = new RallyGuide(new[] { new[] {0f,0f}, new[] {100f,0f}, new[] {200f,0f} }, false);
        Expect(straight.Cues.Count == 0, "No turns invented on a straight");
        Expect(RallyGuide.GradeForRadius(20) == 0 && RallyGuide.GradeForRadius(300) == 6, "Hairpin and open bend scale");
        if (args.Length > 0)
        {
            var points = new List<float[]>();
            using (var r = new BinaryReader(File.OpenRead(args[0])))
            {
                r.ReadInt32(); int count = r.ReadInt32(); r.ReadInt32(); r.ReadInt32();
                for (int i = 0; i < count; i++) {
                    float x = r.ReadSingle(); r.ReadSingle(); float z = r.ReadSingle(); r.ReadSingle(); r.ReadInt32();
                    points.Add(new[] { x,z });
                }
            }
            var monza = new RallyGuide(points.ToArray(), true);
            Console.WriteLine("Monza length: " + monza.Length.ToString("F0") + "m, cues: " + monza.Cues.Count);
            foreach (var cue in monza.Cues) Console.WriteLine((cue.Direction < 0 ? "L" : "R") + cue.Grade +
                " at " + cue.Entry.ToString("F0") + "m, radius " + cue.Radius.ToString("F0") + "m");
            Expect(monza.Cues.Count >= 8 && monza.Cues.Count <= 20, "Plausible Monza curve count");
            float[] gaps; Expect(monza.Upcoming(.99f, out gaps).Length == 3, "Closed route wraps at finish");
        }
        Console.WriteLine("Rally route geometry checks passed"); return 0;
    }
}
