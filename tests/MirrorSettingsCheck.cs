using System;
using System.Globalization;
using System.IO;
using System.Text;

class MirrorSettingsCheck
{
    static void Check(bool value, string message) { if (!value) throw new Exception(message); }
    static void Same(float[] actual, float[] expected, string message) {
        for (int i = 0;i < 6;i++) Check(actual[i] == expected[i],message+" field "+i);
    }
    static int Revision(MirrorSettings settings) { return BitConverter.ToInt32(settings.Packet,8); }
    static void ErrorPacket(MirrorSettings settings,int status) {
        byte[] packet = settings.ErrorPacket;
        Check(packet.Length == 12 && BitConverter.ToInt32(packet,0) == 0x31464341 && BitConverter.ToInt32(packet,4) == 10,"Read/write status packet magic, type and exact length");
        Check(BitConverter.ToInt32(packet,8) == status,"Read/write status code");
    }
    static void Packet(MirrorSettings settings) {
        byte[] packet = settings.Packet;
        Check(packet.Length == 84,"Configuration packet has an exact 84-byte body");
        Check(BitConverter.ToInt32(packet,0) == 0x31464341 && BitConverter.ToInt32(packet,4) == 8,"Magic and configuration type");
        Check(Revision(settings) > 0,"Published packet has a revision");
        for (int view = 1;view <= 3;view++) for (int field = 0;field < 6;field++)
            Check(BitConverter.ToSingle(packet,12+((view-1)*6+field)*4) == settings.Get(view)[field],"View-major float32 field order");
    }
    static void Main(string[] args)
    {
        string folder = Path.Combine(Directory.GetCurrentDirectory(),"artifacts","mirror-settings-check-"+Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(folder); string path = Path.Combine(folder,"mirror-settings.txt");
        CultureInfo culture = CultureInfo.CurrentCulture;
        try {
            var settings = new MirrorSettings(path);
            for (int view = 1;view <= 3;view++) Same(settings.Get(view),MirrorSettings.Defaults(view),"Missing file uses mirror defaults");
            Check(settings.Get(1)[1] == -10.2f && settings.Get(2)[1] == 0 && settings.Get(3)[1] == 10.2f,"Default side directions");
            float[] copy = settings.Get(1); copy[0] = 99;
            Check(settings.Get(1)[0] == 50,"Get returns an independent copy"); Packet(settings);

            for (int field = 0;field < 6;field++) {
                settings.Set(2,field,MirrorSettings.Minimum[field]-10);
                Check(settings.Get(2)[field] == MirrorSettings.Minimum[field],"Clamp below minimum");
                settings.Set(2,field,MirrorSettings.Maximum[field]+10);
                Check(settings.Get(2)[field] == MirrorSettings.Maximum[field],"Clamp above maximum");
            }
            byte[] previousPacket = settings.Packet; string previousText = File.ReadAllText(path);
            for (int field = 0;field < 6;field++) foreach (float value in new float[] { float.NaN,float.PositiveInfinity,float.NegativeInfinity }) settings.Set(2,field,value);
            settings.Set(0,0,60); settings.Set(4,0,60); settings.Set(1,-1,60); settings.Set(1,6,60); settings.Reset(0); settings.Reset(4);
            Check(Object.ReferenceEquals(previousPacket,settings.Packet) && File.ReadAllText(path) == previousText,"Invalid coordinates and nonfinite values do not publish or save");

            CultureInfo.CurrentCulture = new CultureInfo("de-DE");
            settings.Set(1,0,62.5f); settings.Set(1,3,-.34f); settings.Set(3,0,47.25f); settings.Set(3,5,1.75f);
            Check(File.ReadAllText(path) == settings.Text && settings.Text.Contains("62.5"),"Save uses culture-independent decimal points");
            byte[] saved = File.ReadAllBytes(path);
            Check(saved[0] == (byte)'1' && !File.Exists(path+".next"),"Atomic save leaves final UTF-8 file without BOM or staging file");
            var loaded = new MirrorSettings(path);
            for (int view = 1;view <= 3;view++) Same(loaded.Get(view),settings.Get(view),"All six fields persist for each mirror");
            float[] left = settings.Get(1), right = settings.Get(3); int before = Revision(settings);
            settings.Reset(2);
            Same(settings.Get(2),MirrorSettings.Defaults(2),"Selected mirror resets");
            Same(settings.Get(1),left,"Reset preserves left mirror"); Same(settings.Get(3),right,"Reset preserves right mirror");
            Check(Revision(settings) > before,"Reset publishes a newer revision"); Packet(settings);
            loaded = new MirrorSettings(path);
            for (int view = 1;view <= 3;view++) Same(loaded.Get(view),settings.Get(view),"Reset persists without touching other mirrors");
            left = settings.Get(1); right = settings.Get(3); float[] center = settings.Get(2);
            previousText = settings.Text; before = Revision(settings); ErrorPacket(settings,0);
            using (var locked = new FileStream(path,FileMode.Open,FileAccess.Read,FileShare.None)) {
                settings.Set(1,0,left[0]+1);
                Check(!String.IsNullOrEmpty(settings.Error),"Exclusive file lock reports a save error without throwing");
                Same(settings.Get(1),left,"Failed save rolls back the selected mirror");
                Same(settings.Get(2),center,"Failed save preserves center mirror"); Same(settings.Get(3),right,"Failed save preserves right mirror");
                Check(settings.Text == previousText && Revision(settings) > before,"Rollback publishes original values with a new revision"); Packet(settings); ErrorPacket(settings,1);
                loaded = new MirrorSettings(path);
                Check(!String.IsNullOrEmpty(loaded.Error),"Exclusive file lock reports a load error without throwing"); ErrorPacket(loaded,1);
                for (int view = 1;view <= 3;view++) Same(loaded.Get(view),MirrorSettings.Defaults(view),"Failed load uses mirror defaults"); Packet(loaded);
            }
            Check(File.ReadAllText(path) == previousText,"Failed replacement keeps the original persisted settings");
            settings.Set(1,0,left[0]+1);
            Check(String.IsNullOrEmpty(settings.Error) && settings.Get(1)[0] == left[0]+1,"Saving succeeds and clears the error after the file lock is released");
            ErrorPacket(settings,0); Packet(settings);
            loaded = new MirrorSettings(path); Same(loaded.Get(1),settings.Get(1),"Recovered save is persisted");
            if (args.Length > 0) File.WriteAllBytes(args[0],settings.Packet);

            File.WriteAllText(path,"garbage\n0=60,1,2,3,4,5\n4=60,1,2,3,4,5\n1=80,1,2\n1=NaN,Infinity,-Infinity,NaN,Infinity,-Infinity\n2=999,-999,999,-999,999,-999\n3=55,12.5,-3.5,0.4,0.5,-1.25\n",new UTF8Encoding(false));
            loaded = new MirrorSettings(path);
            Same(loaded.Get(1),MirrorSettings.Defaults(1),"Invalid rows and nonfinite stored fields retain defaults");
            Same(loaded.Get(2),new float[] {100,-60,35,-2,1,-3},"Finite stored outliers clamp to each field range");
            Same(loaded.Get(3),new float[] {55,12.5f,-3.5f,.4f,.5f,-1.25f},"Valid stored settings decode independently of current culture"); Packet(loaded);
            Console.WriteLine("Mirror settings: persistence, limits, finite values, isolated reset, packet layout and save-failure recovery passed");
        } finally {
            CultureInfo.CurrentCulture = culture;
            if (File.Exists(path)) File.Delete(path);
            if (File.Exists(path+".next")) File.Delete(path+".next");
            Directory.Delete(folder);
        }
    }
}
