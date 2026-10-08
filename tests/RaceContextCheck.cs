using System;
using System.IO;

class RaceContextCheck
{
    static void Check(bool condition, string message) { if (!condition) throw new Exception(message); }
    static void Main()
    {
        var context = new RaceContext(".");
        string data = "version=1\ncar=test\ntrack=monza\nlaps=2\nsector=1\ncars=5\nlimit=60\ncount=3\npersonal=31000,32000,33000\nworld=30000,31000,32000\nend=ACFLIP1\n";
        Check(context.Parse(data,DateTime.UtcNow),"Complete context accepted");
        Check(context.Personal[1] == 32000 && context.World[2] == 32000 && context.PitLimit == 60 && context.Cars == 5,"Records and actual limit");
        Check(context.Grip == -1 && context.FuelPerLap == 0,"Old relay does not invent new environment fields");
        string environment = data.Replace("end=ACFLIP1\n","grip=0.98\nwetness=0.2\nwater=0\nfuelPerLap=3.2\nend=ACFLIP1\n");
        Check(context.Parse(environment,DateTime.UtcNow) && context.Grip == .98f && context.FuelPerLap == 3.2f,"Real environment and original fuel estimate parsed");
        Check(!context.Parse(environment.Replace("grip=0.98","grip=NaN"),DateTime.UtcNow),"Invalid grip ignored");
        Check(context.Read("test","monza",2,1),"Matching live lap and sector");
        Check(!context.Read("test","monza",2,2) && context.EnvironmentFresh,"Environmental data is independent of sector crossing");
        Check(!context.Parse(data.Replace("end=ACFLIP1\n",""),DateTime.UtcNow),"Partial async writes ignored");
        Check(!context.Parse(data.Replace("31000,32000,33000","31000,32000"),DateTime.UtcNow),"Count mismatch ignored");
        Check(!context.Parse(data.Replace("limit=60","limit=999"),DateTime.UtcNow),"Bad limit ignored");
        Check(!context.Parse(data.Replace("limit=60","limit=NaN"),DateTime.UtcNow),"Nonfinite limit ignored");
        Check(!context.Parse(data.Replace("31000,32000,33000","-1,32000,33000"),DateTime.UtcNow),"Bad sector ignored");
        context.Parse(data,DateTime.UtcNow.AddSeconds(-5));
        Check(!context.Read("test","monza",2,1),"Old sector data not applied");
        Check(!context.Read("other","monza",2,1) && context.PitLimit == 0,"Car mismatch hides speed limit");
        Console.WriteLine("Race context: PB records, actual pit limit, phase, freshness and partial-write checks passed");
    }
}
