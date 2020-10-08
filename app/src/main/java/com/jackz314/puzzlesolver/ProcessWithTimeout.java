package com.jackz314.puzzlesolver;

import android.util.Log;

public class ProcessWithTimeout extends Thread
{
    private Process mProcess;
    private int mExitCode = Integer.MIN_VALUE;

    public ProcessWithTimeout(Process process)
    {
        mProcess = process;
    }

    /**
     * Wait for a given {@link Process process} to finish or wait for given milliseconds then timeout
     * @param timeoutMillSec milliseconds to timeout
     * @return exit code of the process
     */
    public int waitForProcess(int timeoutMillSec)
    {
        this.start();

        try
        {
            this.join(timeoutMillSec);
        }
        catch (InterruptedException e)
        {
            this.interrupt();
        }

        return mExitCode;
    }

    @Override
    public void run()
    {
        try
        { 
            mExitCode = mProcess.waitFor();
        }
        catch (InterruptedException ignore)
        {
            // Do nothing
            Log.d("ProcessWithTimeout", "Process interrupted");
        }
        catch (Exception ex)
        {
            Log.e("ProcessWithTimeout", "Error: "+ex.getMessage());
            ex.printStackTrace();
            // Unexpected exception
        }
    }
}