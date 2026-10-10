using System;
using System.IO;

class ConsoleMirrorCheck
{
    static void Check(bool value,string message) { if (!value) throw new Exception(message); }
    static void Main() {
        string path = Path.Combine(Directory.GetCurrentDirectory(),"artifacts","console-mirror-"+Guid.NewGuid().ToString("N")+".txt");
        try {
            var settings = new MirrorSettings(path); var console = new ConsoleDashboard();
            Check(!console.MirrorKey(new ConsoleKeyInfo('q',ConsoleKey.Q,false,false,false),settings,3),"Normal Q remains available to exit");
            console.MirrorKey(new ConsoleKeyInfo('m',ConsoleKey.M,false,false,false),settings,3);
            console.MirrorKey(new ConsoleKeyInfo('\0',ConsoleKey.RightArrow,false,false,false),settings,3);
            Check(settings.Get(3)[0] == 51 && settings.Get(1)[0] == 50,"M uses active mirror and arrow changes only its field");
            console.MirrorKey(new ConsoleKeyInfo('\0',ConsoleKey.LeftArrow,true,false,false),settings,3);
            Check(settings.Get(3)[0] == 46,"Shift arrow changes five steps");
            console.MirrorKey(new ConsoleKeyInfo('\r',ConsoleKey.Enter,false,false,false),settings,3);
            foreach(char c in "62.59") console.MirrorKey(new ConsoleKeyInfo(c,ConsoleKey.D0,false,false,false),settings,3);
            console.MirrorKey(new ConsoleKeyInfo('\b',ConsoleKey.Backspace,false,false,false),settings,3);
            console.MirrorKey(new ConsoleKeyInfo('\r',ConsoleKey.Enter,false,false,false),settings,3);
            Check(settings.Get(3)[0] == 62.5f && new MirrorSettings(path).Get(3)[0] == 62.5f,"Typed decimals and backspace persist exactly");
            console.MirrorKey(new ConsoleKeyInfo('\0',ConsoleKey.DownArrow,false,false,false),settings,3);
            console.MirrorKey(new ConsoleKeyInfo('2',ConsoleKey.D2,false,false,false),settings,3);
            console.MirrorKey(new ConsoleKeyInfo('\0',ConsoleKey.RightArrow,true,false,false),settings,3);
            Check(settings.Get(2)[1] == 5 && settings.Get(3)[1] == 10.2f,"Number selection and parameter navigation work independently");
            console.MirrorKey(new ConsoleKeyInfo('r',ConsoleKey.R,false,false,false),settings,3);
            Check(settings.Get(2)[1] == 0 && settings.Get(3)[0] == 62.5f,"R resets selected mirror only");
            console.MirrorKey(new ConsoleKeyInfo('\0',ConsoleKey.Escape,false,false,false),settings,3);
            Check(!console.MirrorKey(new ConsoleKeyInfo('q',ConsoleKey.Q,false,false,false),settings,3),"Esc leaves the settings editor");
            Console.WriteLine("Console mirror controls: active view, arrows, Shift, exact entry, mirror selection, reset and exit passed");
        } finally { if(File.Exists(path)) File.Delete(path); if(File.Exists(path+".next")) File.Delete(path+".next"); }
    }
}
