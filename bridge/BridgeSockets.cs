using System;
using System.ComponentModel;
using System.Net.Sockets;
using System.Runtime.InteropServices;

static class BridgeSockets
{
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool SetHandleInformation(IntPtr handle, uint mask, uint flags);

    // ADB may outlive the console. Never let a child retain our listening sockets.
    public static Socket PreventInheritance(Socket socket)
    {
        if (!SetHandleInformation(socket.Handle, 1, 0))
            throw new Win32Exception(Marshal.GetLastWin32Error());
        return socket;
    }
}
