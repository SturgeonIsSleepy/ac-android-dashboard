using System;
using System.Collections.Generic;

// Geometric route cues, generated from the local AI line, not authored pace notes.
sealed class RallyGuide
{
    public sealed class Cue
    {
        public int Direction; // -1 left, +1 right
        public int Grade;     // 1 tight, 6 open, 0 hairpin
        public float Entry, Apex, Radius;
    }
    public readonly List<Cue> Cues = new List<Cue>();
    public readonly float Length;
    readonly bool closed;

    public RallyGuide(float[][] points, bool isClosed)
    {
        closed = isClosed;
        if (points.Length < 3) return;
        var distance = new float[points.Length];
        for (int i = 1; i < points.Length; i++) distance[i] = distance[i-1] + Distance(points[i-1], points[i]);
        Length = distance[distance.Length-1] + (closed ? Distance(points[points.Length-1], points[0]) : 0);
        if (Length < 100) return;
        int count = (int)Math.Ceiling(Length / 5);
        float step = Length / count;
        var samples = new float[count][];
        for (int i = 0; i < count; i++) samples[i] = At(points, distance, i * step);
        var angles = new double[count];
        int window = Math.Max(1, (int)Math.Round(20 / step));
        for (int i = 0; i < count; i++)
        {
            int before = closed ? (i-window+count) % count : Math.Max(0, i-window);
            int after = closed ? (i+window) % count : Math.Min(count-1, i+window);
            double ax = samples[i][0]-samples[before][0], az = samples[i][1]-samples[before][1];
            double bx = samples[after][0]-samples[i][0], bz = samples[after][1]-samples[i][1];
            angles[i] = Math.Atan2(ax*bz-az*bx, ax*bx+az*bz);
        }
        int start = -1, direction = 0, apex = 0;
        double peak = 0, total = 0;
        for (int i = 0; i <= count; i++)
        {
            double angle = i < count ? angles[i] : 0;
            int sign = Math.Abs(angle) >= .035 ? Math.Sign(angle) : 0;
            if (start >= 0 && (sign == 0 || sign != direction))
            {
                if (Math.Abs(total) >= .20 && peak > 0)
                {
                    float radius = (float)(window * step / peak);
                    Cues.Add(new Cue { Direction = direction, Grade = GradeForRadius(radius),
                        Entry = Math.Max(0, start * step + 8), Apex = apex * step, Radius = radius });
                }
                start = -1;
            }
            if (sign != 0)
            {
                if (start < 0) { start = i; direction = sign; apex = i; peak = 0; total = 0; }
                total += angle * step / (window * step);
                if (Math.Abs(angle) > peak) { peak = Math.Abs(angle); apex = i; }
            }
        }
        // AI racing lines can split one long open bend into adjacent curvature clusters.
        for (int i = 1; i < Cues.Count; )
        {
            Cue a = Cues[i-1], b = Cues[i];
            if (a.Direction == b.Direction && a.Grade >= 5 && b.Grade >= 5 && b.Entry-a.Apex < 300)
            {
                a.Apex = b.Apex; a.Radius = Math.Min(a.Radius, b.Radius); a.Grade = GradeForRadius(a.Radius);
                Cues.RemoveAt(i);
            }
            else i++;
        }
    }

    public static int GradeForRadius(float radius)
    {
        return radius < 25 ? 0 : radius < 40 ? 1 : radius < 60 ? 2 : radius < 95 ? 3 :
            radius < 150 ? 4 : radius < 240 ? 5 : 6;
    }

    public Cue[] Upcoming(float progress, out float[] distances)
    {
        var result = new List<Cue>();
        var gaps = new List<float>();
        float position = Math.Max(0, Math.Min(1, progress)) * Length;
        foreach (var cue in Cues)
        {
            if (cue.Apex + 8 >= position) { result.Add(cue); gaps.Add(Math.Max(0, cue.Entry-position)); }
            if (result.Count == 3) break;
        }
        if (closed && result.Count < 3)
            foreach (var cue in Cues)
            {
                if (result.Contains(cue)) continue;
                result.Add(cue); gaps.Add(Math.Max(0, Length-position+cue.Entry));
                if (result.Count == 3) break;
            }
        distances = gaps.ToArray();
        return result.ToArray();
    }

    static float Distance(float[] a, float[] b)
    {
        float x = b[0]-a[0], z = b[1]-a[1]; return (float)Math.Sqrt(x*x+z*z);
    }
    float[] At(float[][] points, float[] distance, float target)
    {
        int index = Array.BinarySearch(distance, target);
        if (index >= 0) return points[index];
        index = ~index;
        if (index >= points.Length)
        {
            float ratio = (target-distance[distance.Length-1]) / Math.Max(.001f, Length-distance[distance.Length-1]);
            return new[] { points[points.Length-1][0] + (points[0][0]-points[points.Length-1][0])*ratio,
                points[points.Length-1][1] + (points[0][1]-points[points.Length-1][1])*ratio };
        }
        int previous = Math.Max(0, index-1);
        float blend = (target-distance[previous]) / Math.Max(.001f, distance[index]-distance[previous]);
        return new[] { points[previous][0] + (points[index][0]-points[previous][0])*blend,
            points[previous][1] + (points[index][1]-points[previous][1])*blend };
    }
}
