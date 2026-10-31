/**
 * Running an external program on the items of a step, exported for the plugins that require this one
 * ({@code requires com.copybot.plugin.exec;}): {@link com.copybot.plugin.exec.process.ProgramResolver} finds the
 * executable, {@link com.copybot.plugin.exec.process.ProcessRunner} runs it with a time limit and keeps the end of
 * its error output, {@link com.copybot.plugin.exec.process.WorkFolder} holds the working files of a step and
 * {@link com.copybot.plugin.exec.process.ItemFiles} turns an item into an input file and an output file back into an
 * item. A change that breaks these classes raises the major version of the plugin.
 */
package com.copybot.plugin.exec.process;
