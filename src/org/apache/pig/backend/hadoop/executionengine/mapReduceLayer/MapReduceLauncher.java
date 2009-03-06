/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.pig.backend.hadoop.executionengine.mapReduceLayer;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.LinkedList;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.jobcontrol.Job;
import org.apache.hadoop.mapred.jobcontrol.JobControl;
import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.backend.executionengine.ExecutionEngine;
import org.apache.pig.backend.hadoop.datastorage.ConfigurationUtil;
import org.apache.pig.backend.hadoop.executionengine.HExecutionEngine;
import org.apache.pig.impl.PigContext;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.MRCompiler.LastInputStreamingOptimizer;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.plans.MROperPlan;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.plans.MRPrinter;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.plans.DotMRPrinter;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.plans.MRStreamHandler;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.plans.POPackageAnnotator;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.plans.PhysicalPlan;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POJoinPackage;
import org.apache.pig.impl.plan.PlanException;
import org.apache.pig.impl.plan.VisitorException;
import org.apache.pig.impl.util.ConfigurationValidator;

/**
 * Main class that launches pig for Map Reduce
 *
 */
public class MapReduceLauncher extends Launcher{
    private static final Log log = LogFactory.getLog(MapReduceLauncher.class);
    @Override
    public boolean launchPig(PhysicalPlan php,
                             String grpName,
                             PigContext pc) throws PlanException,
                                                   VisitorException,
                                                   IOException,
                                                   ExecException,
                                                   JobCreationException {
        long sleepTime = 500;
        MROperPlan mrp = compile(php, pc);
        
        ExecutionEngine exe = pc.getExecutionEngine();
        ConfigurationValidator.validatePigProperties(exe.getConfiguration());
        Configuration conf = ConfigurationUtil.toConfiguration(exe.getConfiguration());
        JobClient jobClient = ((HExecutionEngine)exe).getJobClient();

        JobControlCompiler jcc = new JobControlCompiler(pc, conf);
        
        List<Job> failedJobs = new LinkedList<Job>();
        List<Job> succJobs = new LinkedList<Job>();
        JobControl jc;
        int totalMRJobs = mrp.size();
        int numMRJobsCompl = 0;
        int numMRJobsCurrent = 0;
        double lastProg = -1;

        while((jc = jcc.compile(mrp, grpName)) != null) {
            numMRJobsCurrent = jc.getWaitingJobs().size();

            new Thread(jc).start();
            
            while(!jc.allFinished()){
                try {
                    Thread.sleep(sleepTime);
                } catch (InterruptedException e) {}
                double prog = (numMRJobsCompl+calculateProgress(jc, jobClient))/totalMRJobs;
                if(prog>=(lastProg+0.01)){
                    int perCom = (int)(prog * 100);
                    if(perCom!=100)
                        log.info( perCom + "% complete");
                }
                lastProg = prog;
            }
            numMRJobsCompl += numMRJobsCurrent;
            failedJobs.addAll(jc.getFailedJobs());
            succJobs.addAll(jc.getSuccessfulJobs());
            jcc.moveResults();
            jc.stop(); 
        }

        // Look to see if any jobs failed.  If so, we need to report that.
        if (failedJobs != null && failedJobs.size() > 0) {
            log.error("Map reduce job failed");
            for (Job fj : failedJobs) {
                log.error(fj.getMessage());
                getStats(fj, jobClient, true);
            }
            jc.stop(); 
            return false;
        }

        if(succJobs!=null) {
            for(Job job : succJobs){
                getStats(job,jobClient, false);
            }
        }

        log.info( "100% complete");
        log.info("Success!");
        return true;
    }

    @Override
    public void explain(
            PhysicalPlan php,
            PigContext pc,
            PrintStream ps,
            String format,
            boolean verbose) throws PlanException, VisitorException,
                                   IOException {
        log.trace("Entering MapReduceLauncher.explain");
        MROperPlan mrp = compile(php, pc);

        if (format.equals("text")) {
            MRPrinter printer = new MRPrinter(ps, mrp);
            printer.setVerbose(verbose);
            printer.visit();
        } else {
            ps.println("#--------------------------------------------------");
            ps.println("# Map Reduce Plan                                  ");
            ps.println("#--------------------------------------------------");
            
            DotMRPrinter printer =new DotMRPrinter(mrp, ps);
            printer.setVerbose(verbose);
            printer.dump();
        }
    }

    private MROperPlan compile(
            PhysicalPlan php,
            PigContext pc) throws PlanException, IOException, VisitorException {
        MRCompiler comp = new MRCompiler(php, pc);
        comp.randomizeFileLocalizer();
        comp.compile();
        MROperPlan plan = comp.getMRPlan();
        String lastInputChunkSize = 
            pc.getProperties().getProperty(
                    "last.input.chunksize", POJoinPackage.DEFAULT_CHUNK_SIZE);
        String prop = System.getProperty("pig.exec.nocombiner");
        if (!("true".equals(prop)))  {
            CombinerOptimizer co = new CombinerOptimizer(plan, lastInputChunkSize);
            co.visit();
        }
        
        // optimize key - value handling in package
        POPackageAnnotator pkgAnnotator = new POPackageAnnotator(plan);
        pkgAnnotator.visit();
        
        // check whether stream operator is present
        MRStreamHandler checker = new MRStreamHandler(plan);
        checker.visit();
        
        // optimize joins
        LastInputStreamingOptimizer liso = 
            new MRCompiler.LastInputStreamingOptimizer(plan, lastInputChunkSize);
        liso.visit();
        
        // figure out the type of the key for the map plan
        // this is needed when the key is null to create
        // an appropriate NullableXXXWritable object
        KeyTypeDiscoveryVisitor kdv = new KeyTypeDiscoveryVisitor(plan);
        kdv.visit();
        return plan;
    }
 
}
