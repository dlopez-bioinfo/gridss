package au.edu.wehi.idsv;

import static org.junit.Assert.assertEquals;
import htsjdk.samtools.SAMFileHeader.SortOrder;
import htsjdk.samtools.SAMRecord;

import org.junit.Test;

import com.google.common.collect.Iterators;


public class SequentialSoftClipRealignedRemoteBreakpointFactoryTest extends IntermediateFilesTest {
	@Test
	public void should_get_soft_clip_for_realignment() {
		SAMRecord[] realigned = new SAMRecord[] {
			withReadName("0#1#fr1", Read(2, 10, "15M"))[0],
			withReadName("1#2#fr2", Read(1, 10, "15M"))[0],
			withReadName("1#2#br2", Read(1, 15, "15M"))[0],
			withReadName("2#3#fr3", Read(0, 10, "15M"))[0],
		};
		SAMRecord[] softClip = new SAMRecord[] {
			withReadName("r1", Read(0, 1, "15S15M15S"))[0],
			withReadName("r2", Read(1, 2, "15S15M15S"))[0],
			withReadName("r3", Read(2, 3, "15S15M15S"))[0]
		};
		createInput(softClip);
		ProcessingContext processContext = getCommandlineContext(false);
		SAMEvidenceSource source = new SAMEvidenceSource(processContext, input, false);
		source.completeSteps(ProcessStep.ALL_STEPS);
		createBAM(processContext.getFileSystemContext().getRealignmentBam(input), SortOrder.unsorted, realigned);
		SortRealignedSoftClips srs = new SortRealignedSoftClips(processContext, source);
		srs.process(false);
		
		assertEquals(4, getRSC(source).size());
		
		SequentialSoftClipRealignedRemoteBreakpointFactory ffactory = new SequentialSoftClipRealignedRemoteBreakpointFactory(Iterators.peekingIterator(getRSC(source).iterator()), FWD);
		SequentialSoftClipRealignedRemoteBreakpointFactory bfactory = new SequentialSoftClipRealignedRemoteBreakpointFactory(Iterators.peekingIterator(getRSC(source).iterator()), BWD);
		
		SAMRecord[] orderedRealigned = new SAMRecord[] {
				withReadName("2#3#fr3", Read(0, 10, "15M"))[0],
				withReadName("1#2#fr2", Read(1, 10, "15M"))[0],
				withReadName("2#3#br2", Read(1, 15, "15M"))[0],
				withReadName("0#1#fr1", Read(2, 10, "15M"))[0],
			};
		assertEquals("r3", ffactory.findAssociatedSAMRecord(orderedRealigned[0]).getReadName());
		assertEquals("r2", ffactory.findAssociatedSAMRecord(orderedRealigned[1]).getReadName());
		assertEquals(null, ffactory.findAssociatedSAMRecord(orderedRealigned[2]));
		assertEquals("r1", ffactory.findAssociatedSAMRecord(orderedRealigned[3]).getReadName());
		
		assertEquals(null, bfactory.findAssociatedSAMRecord(orderedRealigned[0]));
		assertEquals(null, bfactory.findAssociatedSAMRecord(orderedRealigned[1]));
		assertEquals("r2", bfactory.findAssociatedSAMRecord(orderedRealigned[2]).getReadName());
		assertEquals(null, bfactory.findAssociatedSAMRecord(orderedRealigned[3]));
	}
}
