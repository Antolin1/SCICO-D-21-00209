/*
 * Copyright (c) 2015-2022 Onder Babur
 * 
 * This file is part of SAMOS Model Analytics and Management Framework.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this 
 * software and associated documentation files (the "Software"), to deal in the Software 
 * without restriction, including without limitation the rights to use, copy, modify, 
 * merge, publish, distribute, sublicense, and/or sell copies of the Software, and to 
 * permit persons to whom the Software is furnished to do so, subject to the following 
 * conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all copies
 *  or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, 
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A 
 * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT 
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF 
 * CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR 
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 * 
 * @author Onder Babur
 * @version 1.0
 */

package nl.tue.set.samos.vsm;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Vector;
import java.util.logging.Logger;

import org.ujmp.core.Matrix;
import org.ujmp.core.SparseMatrix;

import nl.tue.set.samos.common.Configuration;
import nl.tue.set.samos.common.Constants;
import nl.tue.set.samos.common.Parameters;
import nl.tue.set.samos.common.enums.FREQ;
import nl.tue.set.samos.common.enums.IDF;
import nl.tue.set.samos.common.enums.STRUCTURE;
import nl.tue.set.samos.common.enums.VSM_MODE;
import nl.tue.set.samos.common.enums.WEIGHT;
import nl.tue.set.samos.feature.Feature;
import nl.tue.set.samos.feature.NGram;
import nl.tue.set.samos.feature.NTreeApted;
import nl.tue.set.samos.feature.SimpleType;
import nl.tue.set.samos.feature.TypedFeature;
import nl.tue.set.samos.feature.compare.FeatureComparator;
import nl.tue.set.samos.feature.parser.JSONParser;
import nl.tue.set.samos.feature.parser.PlainTextParser;
import nl.tue.set.samos.main.SAMOSRunner;
import node.Node;

/**
 * This class builds a vector space model given a folder with feature files. It constructs a sparse matrix of pairwise similarities of models or model fragments, using
 * 
 * - type-based weighting scheme			whether to consider model element types with equal or different weights 
 * - inverse-document frequency weighting	penalize very common model elements with a lower weight
 * - quadratic vs linear mode				all-pairs approximate comparison (quadratic) or binary occurrence comparison (linear)) 
*/ 
public class VSMBuilder {
	
	final Logger logger = Logger.getLogger(SAMOSRunner.class.getName());
	
	public FeatureComparator featureComparator;
	
	public HashMap<String, Double> weightsMap = new HashMap<String, Double>();	
	public String featureFolder;
	public String vsmFolder;
	
	public static String midfix;
	
	public VSMBuilder(Configuration configuration){
		this.featureFolder = configuration.featureFolder;
		this.vsmFolder = configuration.vsmFolder;
	}
	
	// main method to compute the vsm from a folder of feature files and precomputed nlp
	public void buildVSM(Parameters params, String tag) throws IOException { 
		long startTime = System.currentTimeMillis();
		
		setWeights(params._WEIGHT);
		
		featureComparator = new FeatureComparator(params);
		featureComparator.loadUpCache(featureFolder);

		File dir = new File(featureFolder);
		
		midfix = "";
		File[] ngramFiles = dir.listFiles(new FilenameFilter() { 
	         public boolean accept(File dir, String filename)
	              { return filename.endsWith(midfix + Constants.featureFileSuffix); }
   	} );
		Arrays.sort(ngramFiles, (f1, f2) -> f1.getName().compareTo(f2.getName()));
		
		ArrayList<String> allModelNames = new ArrayList<String>();
		ArrayList<ArrayList<Feature>> allFeatures = new ArrayList<ArrayList<Feature>>();
		LinkedHashSet<Feature> maximalFeatureSet = new LinkedHashSet<Feature>();
		
		int totalVocabularyCount = 0;
		
		Matrix rawTfSparseMatrix = null;
		double[] idfArray = null;
		Matrix targetTfSparseMatrix = null;
		
		// process each feature file
		for(File uf : ngramFiles)
		{
			logger.info("vsm processing model feature file " + uf.getName());
			try {				
				// add model name
				allModelNames.add(uf.getName().replaceFirst(Constants.featureFileSuffix, ""));
				
				BufferedReader br = new BufferedReader(new FileReader(uf));
				
				ArrayList<Feature> features = new ArrayList<Feature>();
				String s = null;
				
				// process all the features per feature file
				while((s = br.readLine()) != null) {
					Feature f = null;
					if (params._STRUCTURE == STRUCTURE.NTREE)
						f = JSONParser.parseText(s);
					else
						f = PlainTextParser.parseText(s);
					 
					if (f == null){
						logger.info("ERROR: parsed null feature: " + f);
					} else {
						features.add(f);
						if (!maximalFeatureSet.contains(f)) // construct a maximal feature set (i.e. all features in all files)
							maximalFeatureSet.add(f);					
					}
				}
				
				allFeatures.add(features);
								
				// clean up
				br.close();
				
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} 
		}
		
		logger.info("Total unique feature count:" + maximalFeatureSet.size());
		
		// regular application of all-pairs comparison: compare all with all, sum up the similarity score
		if (params._VSM_MODE == VSM_MODE.QUADRATIC) 
		{		
			rawTfSparseMatrix = SparseMatrix.Factory.zeros(allFeatures.size(), maximalFeatureSet.size());

			for (int modelNr=0; modelNr<allFeatures.size(); modelNr++)
			{
				int vocabularyIndex = 0;

				logger.info("model or model fragment " + modelNr + "/" + allFeatures.size() + " with feature count = " + allFeatures.get(modelNr).size());

				for (Feature columnFeature: maximalFeatureSet)
				{				
					for (Feature rowFeature: allFeatures.get(modelNr))
					{	
						double temp = rawTfSparseMatrix.getAsDouble(modelNr,vocabularyIndex);
						double comparisonResult = featureComparator.compare(rowFeature, columnFeature);
						// FIXME should never be smaller than 0, safety check here. 
						if (comparisonResult < 0) comparisonResult = 0;
							
						// FIXME uncomment and fix here
						//		if ((!typeExactMatch1 || typeExactMatch2) && typeMatchTotal && rawTfMatrix[modelNr][vocabularyIndex] > 0 )
						//			if (MATCH_LOG_FLAG) matchLog.println("NONTYPE MATCH:" + rowPair + " vs " + columnPair);


						if(params._FREQ == FREQ.FREQ_MAX)
							rawTfSparseMatrix.setAsDouble(Math.max(temp, comparisonResult), modelNr, vocabularyIndex);
						else // if (_FREQ == FREQ.FREQ_SUM)
							rawTfSparseMatrix.setAsDouble(temp + comparisonResult, modelNr, vocabularyIndex);
					}												
					vocabularyIndex++;				
				}

			}
		}
		
		else // if LINEAR VSM: just binary comparison (feature is present or not)
		{		
			rawTfSparseMatrix = SparseMatrix.Factory.zeros(allFeatures.size(), maximalFeatureSet.size());

			Feature[] maximalFeatureArray = new Feature[maximalFeatureSet.size()];
			maximalFeatureSet.toArray(maximalFeatureArray);
			List<Feature> maximalFeatureList = Arrays.asList(maximalFeatureArray);
			
			for (int modelNr=0; modelNr<allFeatures.size(); modelNr++)
			{
				int vocabularyIndex = 0;

				logger.info(modelNr + " feature count " + allFeatures.get(modelNr).size());
										
				for (Feature rowFeature: allFeatures.get(modelNr))
				{	
					vocabularyIndex = maximalFeatureList.indexOf(rowFeature);		
					
					double temp = rawTfSparseMatrix.getAsDouble(modelNr,vocabularyIndex);
					
					// FIXME uncomment and fix here
					//		if ((!typeExactMatch1 || typeExactMatch2) && typeMatchTotal && rawTfMatrix[modelNr][vocabularyIndex] > 0 )
					//			if (MATCH_LOG_FLAG) matchLog.println("NONTYPE MATCH:" + rowPair + " vs " + columnPair);

					if(params._FREQ == FREQ.FREQ_MAX)
						rawTfSparseMatrix.setAsDouble(1, modelNr, vocabularyIndex);
					else // if (_FREQ == FREQ.FREQ_SUM)
						rawTfSparseMatrix.setAsDouble(temp + 1, modelNr, vocabularyIndex);
				}																					
			}
		}
			
		totalVocabularyCount = maximalFeatureSet.size();
		double totalDocs = allFeatures.size();
	
		if (params._WEIGHT == WEIGHT.RAW)
			targetTfSparseMatrix = rawTfSparseMatrix;
		else { // some type-based weighting scheme			
			int j = -1;
			for (Feature f : maximalFeatureSet)
			{
				j++;
				double weight = 0;
				try{
					// default  - set all to 1 no matter what
					// weight = 1;
					// experimental - just consider the column n-gram (ignore the row & comparison), average the vertex weights 					
//					for (Pair<String, String> p : maximalFeatureSet.get(j).pairs)
//						weight = weight + weightsMap.get(p.x);
//					weight = weight / maximalNgramVector.get(j).n;
					// not going for the experimental weighing for now 
					
					// DEFAULT WEIGHT
					weight = 1.0;
					
					if (f instanceof NTreeApted){
						Feature rootNode = ((NTreeApted) f).aptedTree.getNodeData();
						Feature simpleRoot = ((NGram) rootNode).get(0);
						if (simpleRoot instanceof TypedFeature){
							// normally an error if not in the map FIXME
							try{
								// weight based on the first element
//								weight = weightsMap.get(((TypedFeature) firstGram).getType());
								
								// weight based on the average
								weight = 0;
								int total = 0;
								
								// add root
								weight += weightsMap.get(((TypedFeature) simpleRoot).getType());
								total++;
							
								// add children
								Vector<Node<Feature>> children = ((NTreeApted) f).aptedTree.getChildren();
								for (int k=0; k<children.size(); k++){
									Feature node = children.get(k).getNodeData();		
									// HACK get the 1st (not 0th, it's always an edge) in the ngram
									if (node instanceof NGram && ((NGram) node).n > 1) {
										
										String edgeType = ((TypedFeature)((NGram) node).get(0)).getType();
										if (edgeType.equalsIgnoreCase(Constants.CONTAINS)) {
											total++;
											weight += weightsMap.get(((TypedFeature)((NGram) node).get(1)).getType());
										}
										else if (edgeType.equals(Constants.THROWS) || edgeType.equals(Constants.HAS_SUPERTYPE)) {
											total++;
											weight += weightsMap.get(edgeType);
										}
										else {
											logger.info("forgot to add weight for edge type?? " + edgeType);
										}
									}
								}
								weight = weight / (1.0 * total);
							} catch(Exception ex) {
								ex.printStackTrace();
								weight = 1.0;
							}
						}
							
					}
					
					else if (f instanceof NGram){
						NGram ng = (NGram) f;
						Feature firstGram = ng.get(0);
						
						if (firstGram instanceof TypedFeature) {
							// normally an error if not in the map FIXME
							try{
								// weight based on the first element
//								weight = weightsMap.get(((TypedFeature) firstGram).getType());
								
								// weight based on the average
								weight = 0;
								int total = 0;
								for (int k=0; k<ng.n; k++){
									Feature fn = ng.get(k);
									if (fn instanceof SimpleType) {
										String edgeType = ((SimpleType)fn).getType();
										if (edgeType.equals(Constants.CONTAINS))
											continue;
										else if (edgeType.equals(Constants.THROWS) || edgeType.equals(Constants.HAS_SUPERTYPE)) {
											total++;
											weight += weightsMap.get(edgeType);
											k++; // iterate one further
										}
										else {
											logger.info("forgot to add weight for edge type?? " + edgeType);
										}
									}
										
									else if (fn instanceof TypedFeature) {
										total++;
										weight += weightsMap.get(((TypedFeature) fn).getType());
									}
								}
								weight = weight / (1.0 * total);
							} catch(Exception ex) {
								ex.printStackTrace();
								weight = 1.0;
							}
						}
					}
					
				} catch(NullPointerException ex){
					logger.info("Error " + j + " << " + f);
					ex.printStackTrace();
					System.exit(-1);
				}
				for (int i=0; i<totalDocs; i++)
				{
					rawTfSparseMatrix.setAsDouble(rawTfSparseMatrix.getAsDouble(i, j) * weight, i,j);
				}
			}
			targetTfSparseMatrix = rawTfSparseMatrix;
			
		}		

		// (log((total documents)/(number of docs with the term))
		

		if (params._IDF == IDF.NO_IDF)
			;
		else // idf weighting scheme 
		{
			idfArray = new double[totalVocabularyCount];
			Arrays.fill(idfArray, 0.0);
			
			for (int j=0; j<totalVocabularyCount; j++)
			{
				int numOfDocsWithTerm = 0;
				for (int i=0; i<totalDocs; i++)
				{
					//if (rawTfMatrix[i][j] == 1.0) // CAREFUL, MULTIPLIED WITH WEIGHTS ALREADY
//					if (targetTfMatrix[i][j] > 0)
					if (targetTfSparseMatrix.getAsDouble(i, j) > 0)
						numOfDocsWithTerm++; 
				}
				if (params._IDF != IDF.NO_IDF)
				{
					int sum = params._IDF == IDF.LOG?0:1;
					if (numOfDocsWithTerm == 0)
						logger.info("ERROR ZERO numOfDocs at " + j + " = " + numOfDocsWithTerm);
					idfArray[j] = Math.log10(sum + (1.0 * totalDocs / numOfDocsWithTerm)); // note idf can have more variations
					if (idfArray[j] == Double.NaN)
						logger.info("ERROR NaN idf at " + j + " = " + idfArray[j]);
					if (idfArray[j] == Double.POSITIVE_INFINITY)
						logger.info("ERROR infinity idf at " + j + " = " + idfArray[j]);
				}
			}
			
			
			for (int i=0; i<allModelNames.size(); i++)
			{
				for (int j=0; j<totalVocabularyCount; j++)
					targetTfSparseMatrix.setAsDouble(targetTfSparseMatrix.getAsDouble(i, j) * idfArray[j], i, j);
			}
			
		}		
		
		DumpSparseMatrixToCsv(maximalFeatureSet, targetTfSparseMatrix, "vsm-" + tag + ".csv");

		logger.info("ELAPSED TIME: " + (System.currentTimeMillis() - startTime));
	}
	
	// export matrix to a csv file
	public void DumpSparseMatrixToCsv(LinkedHashSet<Feature> maximalFeatureSet, Matrix sparseMatrix, String filename) throws IOException
	{
		FileWriter fout = new FileWriter(vsmFolder + filename);
		
		long rowCount = sparseMatrix.getSize(0), columnCount = sparseMatrix.getSize(1);
		for (int i=0; i<rowCount; i++){
			for (int j=0; j<columnCount; j++){
				fout.write((new Double(sparseMatrix.getAsDouble(i, j))).toString());
				//fout.write(String.format("%.8f", new Double(row[i] * multiplier)));
				
				if (j < columnCount-1)
					fout.write(",");
			}
			fout.write("\n");
		}		
		fout.close();
	}

	// different weighting schemes (experimental)
	public void setWeights(WEIGHT _WEIGHT){
		weightsMap = new HashMap<String, Double>();
				
		if (_WEIGHT == WEIGHT.RAW){
			weightsMap.put("EPackage", 1.0);
			weightsMap.put("EDataType", 1.0);
			
			weightsMap.put("EClass", 1.0);
			weightsMap.put("EReference", 1.0);
			weightsMap.put("EAttribute", 1.0);
			
			weightsMap.put("EEnum", 1.0);
			
			weightsMap.put("EEnumLiteral", 1.0);
			
			weightsMap.put("EOperation", 1.0);
			weightsMap.put("EParameter", 1.0);
			
			weightsMap.put(Constants.HAS_SUPERTYPE, 1.0);
			weightsMap.put(Constants.THROWS, 1.0);
		}
		else if (_WEIGHT == WEIGHT.WEIGHT_1) {
			weightsMap.put("EPackage", 1.0);
			weightsMap.put("EDataType", 0.2);
			
			weightsMap.put("EClass", 1.0);
			weightsMap.put("EReference", 0.5);
			weightsMap.put("EAttribute", 0.5);
			
			weightsMap.put("EEnum", 1.0);
			
			weightsMap.put("EEnumLiteral", 1.0);
			
			weightsMap.put("EOperation", 0.3);
			weightsMap.put("EParameter", 0.1);
			
			weightsMap.put(Constants.HAS_SUPERTYPE, 0.2);
			weightsMap.put(Constants.THROWS, 0.1);
		}
		else if (_WEIGHT == WEIGHT.WEIGHT_2) {
			weightsMap.put("EPackage", 2.0);
			weightsMap.put("EDataType", 0.1);
			
			weightsMap.put("EClass", 1.0);
			weightsMap.put("EReference", 0.5);
			weightsMap.put("EAttribute", 0.5);
			
			weightsMap.put("EEnum", 1.0);
			
			weightsMap.put("EEnumLiteral", 1.0);
			
			weightsMap.put("EOperation", 0.2);
			weightsMap.put("EParameter", 0.01);
			
			weightsMap.put(Constants.HAS_SUPERTYPE, 0.2); 
			weightsMap.put(Constants.THROWS, 0.1);
		}
		
	}
	
}
