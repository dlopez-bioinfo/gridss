package au.edu.wehi.idsv;

import htsjdk.samtools.Cigar;
import htsjdk.samtools.CigarElement;
import htsjdk.samtools.CigarOperator;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import au.edu.wehi.idsv.sam.CigarUtil;
import au.edu.wehi.idsv.sam.SAMRecordUtil;
import au.edu.wehi.idsv.sam.SamTags;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Bytes;

public final class AssemblyFactory {
	private static final Log log = Log.getInstance(AssemblyFactory.class);
	private AssemblyFactory() { } 
	/**
	 * Creates an assembly 
	 * @param processContext context
	 * @param source assembly source
	 * @param direction direction of breakend
	 * @param evidence evidence supporting the assembly breakend
	 * @param anchorReferenceIndex contig of anchored bases 
	 * @param anchorBreakendPosition genomic position of anchored base closest breakend
	 * @param anchoredBaseCount number of anchored bases in assembly
	 * @param baseCalls assembly base sequence as per a positive strand read over the anchor
	 * @param baseQuals assembly base qualities
	 * @param normalBaseCount number of assembly bases contributed by normal evidence sources
	 * @param tumourBaseCount number of assembly bases contributed by tumour evidence sources
	 * @return assembly evidence for the given assembly
	 */
	public static SAMRecordAssemblyEvidence createAnchoredBreakend(
			ProcessingContext processContext,
			AssemblyEvidenceSource source, BreakendDirection direction,
			Collection<String> evidence,
			int anchorReferenceIndex, int anchorBreakendPosition, int anchoredBaseCount,
			byte[] baseCalls, byte[] baseQuals,
			int[] baseCounts) {
		BreakendSummary breakend = new BreakendSummary(anchorReferenceIndex, direction, anchorBreakendPosition, anchorBreakendPosition);
		SAMRecord r = createAssemblySAMRecord(evidence, processContext.getBasicSamHeader(), source, breakend,
				breakend.direction == BreakendDirection.Forward ? anchoredBaseCount : 0,
				breakend.direction == BreakendDirection.Backward ? anchoredBaseCount : 0,
				baseCalls, baseQuals, baseCounts);
		SAMRecordAssemblyEvidence assembly = hydrate(source, r);
		return assembly;
	}
	public static SAMRecordAssemblyEvidence createAnchoredBreakpoint(
			ProcessingContext processContext, AssemblyEvidenceSource source,
			Collection<String> evidence,
			int startAnchorReferenceIndex, int startAnchorPosition, int startAnchorBaseCount,
			int endAnchorReferenceIndex, int endAnchorPosition, int endAnchorBaseCount,
			byte[] baseCalls, byte[] baseQuals, int[] baseCounts) {
		BreakpointSummary bp = new BreakpointSummary(
				startAnchorReferenceIndex, BreakendDirection.Forward, startAnchorPosition, startAnchorPosition,
				endAnchorReferenceIndex, BreakendDirection.Backward, endAnchorPosition, endAnchorPosition);
		assert(startAnchorBaseCount > 0);
		assert(endAnchorBaseCount > 0);
		SAMRecord r = createAssemblySAMRecord(evidence, processContext.getBasicSamHeader(), source, bp,
				startAnchorBaseCount,
				endAnchorBaseCount,
				baseCalls, baseQuals, baseCounts);
		SAMRecordAssemblyEvidence assembly = hydrate(source, r);
		return assembly;
	}
	/**
	 * Creates an assembly whose breakpoint cannot be exactly anchored to the reference  
	 * @param processContext context
	 * @param source assembly source
	 * @param direction direction of breakend
	 * @param evidence evidence supporting the assembly breakend
	 * @param baseCalls assembly base sequence as per a positive strand read into a putative anchor
	 * @param baseQuals assembly base qualities
	 * @param normalBaseCount number of assembly bases contributed by normal evidence sources
	 * @param tumourBaseCount number of assembly bases contributed by tumour evidence sources
	 * @return assembly evidence for the given assembly
	 */
	public static SAMRecordAssemblyEvidence createUnanchoredBreakend(
			ProcessingContext processContext,
			AssemblyEvidenceSource source,
			BreakendSummary breakend,
			Collection<String> evidence,
			byte[] baseCalls, byte[] baseQuals,
			int[] baseCounts) {
		SAMRecord r = createAssemblySAMRecord(evidence, processContext.getBasicSamHeader(), source, breakend,
				0, 0,
				baseCalls, baseQuals, baseCounts);
		SAMRecordAssemblyEvidence assembly = hydrate(source, r);
		return assembly;
	}
	/**
	 * Updates the given assembly to incorporate the given realignment of the assembly breakend
	 * @param processContext
	 * @return
	 */
	public static SAMRecordAssemblyEvidence incorporateRealignment(ProcessingContext processContext, SAMRecordAssemblyEvidence assembly, SAMRecord realignment) {
		if (realignment == null) return assembly;
		SAMRecordAssemblyEvidence a = (SAMRecordAssemblyEvidence)assembly;
		if (realignment.getReadUnmappedFlag() || !processContext.getRealignmentParameters().realignmentPositionUnique(realignment)) {
			// Breakend did not align well enough for us to call a breakpoint
			return new SAMRecordAssemblyEvidence(a.getEvidenceSource(), assembly, realignment);
		} else {
			return new RealignedSAMRecordAssemblyEvidence(a.getEvidenceSource(), assembly, realignment);
		}
	}
	/**
	 * Rehydrates an assembly that has been persisted as a SAMRecord
	 * @param processContext
	 * @param record assembly SAMRecord
	 * @return Assembly evidence
	 */
	public static SAMRecordAssemblyEvidence hydrate(AssemblyEvidenceSource source, SAMRecord record) {
		BreakendDirection dir = SAMRecordAssemblyEvidence.getBreakendDirection(record);
		if (!SAMRecordUtil.isReferenceAlignment(record) && (dir == null || SAMRecordUtil.getSoftClipLength(record, dir) == 0)) {
			return new SmallIndelSAMRecordAssemblyEvidence(source,  record);
		} else {
			return new SAMRecordAssemblyEvidence(source, record, null);
		}
	}
	private static final byte[][] PAD_BASES = new byte[][] { new byte[] {}, new byte[] { 'N' }, new byte[] { 'N', 'N' } };
	private static final byte[][] PAD_QUALS = new byte[][] { new byte[] {}, new byte[] { 0 }, new byte[] { 0, 0 } };
	private static SAMRecord createAssemblySAMRecord(
			Collection<String> evidence,
			SAMFileHeader samFileHeader, AssemblyEvidenceSource source,
			BreakendSummary breakend,
			int startAnchoredBaseCount,
			int endAnchoredBaseCount,
			byte[] baseCalls, byte[] baseQuals,
			int[] baseCounts) {
		assert(startAnchoredBaseCount >= 0);
		assert(endAnchoredBaseCount >= 0);
		assert(startAnchoredBaseCount + endAnchoredBaseCount <= baseCalls.length);
		assert(baseCalls.length == baseQuals.length);
		assert(breakend != null);
		SAMRecord record = new SAMRecord(samFileHeader);
		record.setReferenceIndex(breakend.referenceIndex);
		record.setReadName(source.getContext().getAssemblyIdGenerator().generate(breakend, baseCalls, startAnchoredBaseCount, endAnchoredBaseCount));
		if (startAnchoredBaseCount == 0 && endAnchoredBaseCount == 0) {
			assert(!(breakend instanceof BreakpointSummary));
			// SAM spec requires at least one mapped base
			// to conform to this, we add a placeholder mismatched bases to our read
			// in the furthest anchor position
			// and represent the breakend confidence interval as an N
			// interval anchored by Xs
			record.setAlignmentStart(breakend.start);
			LinkedList<CigarElement> ce = new LinkedList<CigarElement>();
			int len = breakend.end - breakend.start + 1;
			int padBases;
			if (len <= 2) {
				ce.add(new CigarElement(len, CigarOperator.X));
				padBases = len;
			} else {
				ce.add(new CigarElement(1, CigarOperator.X));
				ce.add(new CigarElement(len - 2, CigarOperator.N));
				ce.add(new CigarElement(1, CigarOperator.X));
				padBases = 2;
			}
			if (breakend.direction == BreakendDirection.Forward) {
				ce.addLast(new CigarElement(baseCalls.length, CigarOperator.SOFT_CLIP));
				record.setCigar(new Cigar(ce));
				record.setReadBases(Bytes.concat(PAD_BASES[padBases], baseCalls));
				record.setBaseQualities(Bytes.concat(PAD_QUALS[padBases], baseQuals));
			} else {
				ce.addFirst(new CigarElement(baseCalls.length, CigarOperator.SOFT_CLIP));
				record.setCigar(new Cigar(ce));
				record.setReadBases(Bytes.concat(baseCalls, PAD_BASES[padBases]));
				record.setBaseQualities(Bytes.concat(baseQuals, PAD_QUALS[padBases]));
			}
		} else {
			record.setReadBases(baseCalls);
			record.setBaseQualities(baseQuals);
			if (breakend.start != breakend.end) {
				throw new IllegalArgumentException("Imprecisely anchored breakends not supported by this constructor");
			}
			if (startAnchoredBaseCount > 0 && endAnchoredBaseCount > 0) {
				// This is a breakpoint alignment spanning the entire event
				BreakpointSummary bp = (BreakpointSummary)breakend;
				record.setAlignmentStart(breakend.start - startAnchoredBaseCount + 1);
				List<CigarElement> c = new ArrayList<CigarElement>(4);
				int insSize = baseCalls.length - startAnchoredBaseCount - endAnchoredBaseCount;
				int delSize = bp.start2 - bp.start - 1;
				c.add(new CigarElement(startAnchoredBaseCount, CigarOperator.MATCH_OR_MISMATCH));
				if (insSize != 0) {
					c.add(new CigarElement(insSize, CigarOperator.INSERTION));
				}
				if (delSize != 0) {
					c.add(new CigarElement(delSize, CigarOperator.DELETION));
					if (delSize < 0) {
						// negative relative alignment position not representable in SAM
						// as all CIGAR element lengths must be positive in size.
						c = CigarUtil.encodeNegativeDeletion(c);
					}
				}
				c.add(new CigarElement(endAnchoredBaseCount, CigarOperator.MATCH_OR_MISMATCH));
				record.setCigar(new Cigar(c));
			} else if (startAnchoredBaseCount > 0) {
				assert(!(breakend instanceof BreakpointSummary));
				assert(breakend.direction == BreakendDirection.Forward);
				record.setAlignmentStart(breakend.start - startAnchoredBaseCount + 1);
				record.setCigar(new Cigar(ImmutableList.of(
						new CigarElement(startAnchoredBaseCount, CigarOperator.MATCH_OR_MISMATCH),
						new CigarElement(baseCalls.length - startAnchoredBaseCount, CigarOperator.SOFT_CLIP))));
			} else { // endAnchoredBaseCount > 0
				assert(!(breakend instanceof BreakpointSummary));
				assert(breakend.direction == BreakendDirection.Backward);
				record.setAlignmentStart(breakend.start);
				record.setCigar(new Cigar(ImmutableList.of(
						new CigarElement(baseCalls.length - endAnchoredBaseCount, CigarOperator.SOFT_CLIP),
						new CigarElement(endAnchoredBaseCount, CigarOperator.MATCH_OR_MISMATCH))));
			}
		}
		setEvidenceIDs(record, evidence);
		record.setAttribute(SamTags.ASSEMBLY_BASE_COUNT, baseCounts);
		if (!(breakend instanceof BreakpointSummary)) {
			record.setAttribute(SamTags.ASSEMBLY_DIRECTION, breakend.direction.toChar());
		}
		return record;
	}
	private static void setEvidenceIDs(SAMRecord r, Collection<String> evidence) {
		List<String> evidenceIDs;
		if (evidence == null) {
			evidenceIDs = new ArrayList<String>();
		} else {
			evidenceIDs = new ArrayList<String>(evidence);
		}
		// Set deterministic ordering of evidence to allow for SAM diff
		Collections.sort(evidenceIDs);
		StringBuilder sb = new StringBuilder();
		for (String id : evidenceIDs) {
			assert(!id.contains(SAMRecordAssemblyEvidence.COMPONENT_EVIDENCEID_SEPARATOR));
			sb.append(id);
			sb.append(SAMRecordAssemblyEvidence.COMPONENT_EVIDENCEID_SEPARATOR);
		}
		if (evidenceIDs.size() > 0) {
			sb.deleteCharAt(sb.length() - 1);
		}
		r.setAttribute(SamTags.ASSEMBLY_COMPONENT_EVIDENCEID, sb.toString());
		assert(ensureUniqueEvidenceID(evidenceIDs));
	}
	private static boolean ensureUniqueEvidenceID(Collection<String> evidence) {
		boolean isUnique = true;
		Set<String> map = new HashSet<String>();
		for (String id : evidence) {
			if (map.contains(id)) {
				log.error("Found evidenceID " + id + " multiple times in assembly");
				isUnique = false;
			}
			map.add(id);
		}
		return isUnique;
	}
}

