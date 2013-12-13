package org.wikipedia.miner.extract.pageDepth;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.avro.mapred.AvroJob;
import org.apache.avro.mapred.Pair;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.Counters;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.JobStatus;
import org.apache.hadoop.mapred.RunningJob;
import org.wikipedia.miner.extract.DumpExtractor;
import org.wikipedia.miner.extract.DumpExtractor2;
import org.wikipedia.miner.extract.Step;
import org.wikipedia.miner.extract.model.struct.PageDetail;
import org.wikipedia.miner.extract.model.struct.PageKey;
import org.wikipedia.miner.extract.pageDepth.DepthCombinerOrReducer.DepthCombiner;
import org.wikipedia.miner.extract.pageDepth.DepthCombinerOrReducer.DepthReducer;
import org.wikipedia.miner.extract.pageDepth.DepthCombinerOrReducer.Counts;
import org.wikipedia.miner.extract.pageSummary.PageSummaryStep.Unforwarded;
import org.wikipedia.miner.extract.util.UncompletedStepException;


public class PageDepthStep extends Step {

	private int iteration ;
	private int finalSummaryIteration ;

	private Map<Counts,Long> counts ;



	public PageDepthStep(Path workingDir, int iteration, int finalSummaryIteration) throws IOException {
		super(workingDir);

		this.iteration = iteration ;
		this.finalSummaryIteration = finalSummaryIteration ;
	}

	@Override
	public int run(String[] args) throws Exception {
		
		if (isFinished()) {
			loadCounts() ;
			return 0 ;
		} else {
			reset() ;
		}
		
		JobConf conf = new JobConf(PageDepthStep.class);
		DumpExtractor2.configureJob(conf, args) ;

		conf.setJobName("WM: page depth (" + iteration + ")");
		
		if (iteration == 0) 
			FileInputFormat.setInputPaths(conf, getWorkingDir() + Path.SEPARATOR + "pageSummary_" + finalSummaryIteration);
		else
			FileInputFormat.setInputPaths(conf, getWorkingDir() + Path.SEPARATOR + "pageDepth_" + (iteration-1));
		
		AvroJob.setInputSchema(conf, Pair.getPairSchema(PageKey.getClassSchema(),PageDetail.getClassSchema()));
		AvroJob.setOutputSchema(conf, Pair.getPairSchema(PageKey.getClassSchema(),PageDetail.getClassSchema()));
		
		
		DistributedCache.addCacheFile(new Path(conf.get(DumpExtractor.KEY_LANG_FILE)).toUri(), conf);
		
		AvroJob.setMapperClass(conf, DepthMapper.class);
		AvroJob.setCombinerClass(conf, DepthCombiner.class) ;
		AvroJob.setReducerClass(conf, DepthReducer.class);
		
		FileOutputFormat.setOutputPath(conf, getDir());
		
		RunningJob runningJob = JobClient.runJob(conf);
	
		if (runningJob.getJobState() == JobStatus.SUCCEEDED) {	
			finish(runningJob) ;
			return 0 ;
		}
		
		throw new UncompletedStepException() ;
	}

	public boolean furtherIterationsRequired() {
		return counts.get(Counts.unforwarded) > 0 ;
	}


	@Override
	public String getDirName() {
		// TODO Auto-generated method stub
		return "pageDepth_" + iteration ;

	}

	private Path getCountsPath() {
		return new Path(getDir() + Path.SEPARATOR + "counts") ;
	}

	
	
	private void saveCounts() throws IOException {
		FSDataOutputStream out = getHdfs().create(getCountsPath());
		
		for (Counts c:Counts.values()) {
			
			out.writeUTF(c.name()) ;
			
			Long count = counts.get(c) ;
			if (count != null)
				out.writeLong(count) ;
			else
				out.writeLong(0L) ;
		}
		
		out.close();
	}
	
	private void loadCounts() throws IOException {
		
		counts = new HashMap<Counts,Long>() ;
		
		
		FSDataInputStream in = getHdfs().open(getCountsPath());
		
		while (in.available() > 0) {
			
			String c = in.readUTF() ;
			
			Long count = in.readLong() ;
			
			counts.put(Counts.valueOf(c), count) ;
		}
	
		in.close() ;
		
	}



	public void finish(RunningJob runningJob) throws IOException {

		super.finish(runningJob) ;

		counts = new HashMap<Counts,Long>() ;

		for (Counts count:Counts.values()) {
			
			Counters.Counter counter = runningJob.getCounters().findCounter(count) ;
			if (counter != null)
				counts.put(count, counter.getCounter()) ;
			else
				counts.put(count, 0L) ;
		}

		saveCounts() ;

	}
}
