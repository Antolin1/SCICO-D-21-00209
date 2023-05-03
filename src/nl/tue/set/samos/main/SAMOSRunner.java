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

package nl.tue.set.samos.main;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import nl.tue.set.samos.common.Configuration;
import nl.tue.set.samos.common.Constants;
import nl.tue.set.samos.common.FileUtil;
import nl.tue.set.samos.common.Parameters;
import nl.tue.set.samos.common.Util;
import nl.tue.set.samos.common.enums.CTX_MATCH;
import nl.tue.set.samos.common.enums.FREQ;
import nl.tue.set.samos.common.enums.GOAL;
import nl.tue.set.samos.common.enums.IDF;
import nl.tue.set.samos.common.enums.NGRAM_CMP;
import nl.tue.set.samos.common.enums.SCOPE;
import nl.tue.set.samos.common.enums.SERIALIZATION;
import nl.tue.set.samos.common.enums.STRUCTURE;
import nl.tue.set.samos.common.enums.SYNONYM;
import nl.tue.set.samos.common.enums.SYNONYM_TRESHOLD;
import nl.tue.set.samos.common.enums.TYPE_MATCH;
import nl.tue.set.samos.common.enums.UNIT;
import nl.tue.set.samos.common.enums.VSM_MODE;
import nl.tue.set.samos.common.enums.WEIGHT;
import nl.tue.set.samos.crawl.Crawler;
import nl.tue.set.samos.extract.EcoreExtractorImpl;
import nl.tue.set.samos.extract.IExtractor;
import nl.tue.set.samos.feature.NTreeApted;
import nl.tue.set.samos.feature.parser.JSONParser;
import nl.tue.set.samos.nlp.NLP;
import nl.tue.set.samos.stats.RAnalyzer;
import nl.tue.set.samos.vsm.VSMBuilder;
import com.opencsv.CSVReader;


/**
 * This is the main entry point and runner class for SAMOS. It gets the configuration options via CLI and runs SAMOS with those predefined settings:
 *  
 *	--crawl atlzoo urlPattern
 *	Crawls the atlzoo metamodel dataset, including metamodels of bibliography and conference management.
 *
 *	--crawl zenodo urlPattern
 *	Crawls the zenodo model clustering dataset. 
 *
 *	--cluster FOLDER_NAME
 *  Runs clustering with standard settings for all the metamodels under the folder data/[FOLDER_NAME]. E.g. run with --cluster atlzoo for the crawled atlzoo dataset. 
 *
 *	--clone FOLDER_NAME
 *	Runs clone detection with standard settings for all the metamodels under the folder data/[FOLDER_NAME]. E.g. run with --cluster atlzoo for the crawled atlzoo dataset. 
 */
public class SAMOSRunner {
	
    private static Logger logger = LoggerFactory.getLogger(SAMOSRunner.class);

    // CLI interaction with standard functionalities of SAMOS
	public static void main(String[] args) {
		
		SAMOSRunner samos = new SAMOSRunner(args);		
		
		try {
			// standard settings for clustering with UNIGRAM-NAME combination for the model scope
			final SCOPE _SCOPE = SCOPE.MODEL; 
			final UNIT _UNIT = UNIT.NAME; 
			final STRUCTURE _STRUCTURE = STRUCTURE.UNIGRAM; 
				
			// preprocess model element names, tokenize them and lemmatize the tokens 
			samos.PREPROCESS_TOKENIZE = true;
			samos.PREPROCESS_LEMMATIZE = true;
				
			// set the threshold for including model elements in clustering (i.e. filter out the smaller ones)
			samos.MIN_MODEL_ELEMENT_COUNT_PER_FRAGMENT = 1;
				
			// run the three components: feature extraction, vsm computation and clustering
			logger.info("Starting SAMOS with goal " + samos.configuration._GOAL + " " + "and parameters " + _SCOPE + "-" + _UNIT  + "-" + _STRUCTURE);
			samos.extractFeatures(_SCOPE, _UNIT, _STRUCTURE);
			samos.buildVSMForClustering(_UNIT, _STRUCTURE);
			samos.clusterInR();

			generatePredictions(args[0], "results/");
			
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
		
	public HashMap<String, ArrayList<String>> featureMap;
	public VSMBuilder vsmBuilder;
	public RAnalyzer r;
	public String targetExtension = ".ecore";
//	public String modelFolder, dataFolder, featureFolder, vsmFolder, rFolder; // go into configuration	
//	public GOAL _GOAL; // go into configuration
	public Configuration configuration;
	public boolean PREPROCESS_TOKENIZE;
	public boolean PREPROCESS_LEMMATIZE;
	public int MIN_MODEL_ELEMENT_COUNT_PER_FRAGMENT;

	
	public SAMOSRunner(String[] args) {
		loadConfiguration(args);
		r = new RAnalyzer();
		vsmBuilder = new VSMBuilder(configuration);
		featureMap = null;
	}
	
	// CONFIG
	// set up configuration for folders and goal
	private void loadConfiguration(String[] args){
		String root = args[0];
		String hyper = args[1];
		
		try{			
			configuration = new Configuration();
			configuration.dataFolder = getInputFolder(root);
			configuration.featureFolder = "features";
			configuration.vsmFolder = "vsm/";		
			configuration.rFolder = "results/";
			configuration._GOAL = GOAL.CLUSTER;
			configuration.clusters = getNclusters(hyper);
			configuration.root = root;
			
		} catch(Exception ex) {ex.printStackTrace();}
				
	}
	
	
	// CONFIG END
	
	private static void generatePredictions(String root, String rFolder) throws IOException {
		String csv = rFolder + File.separator + "clusterLabels.csv";
		List<String> columnData = new ArrayList<>();

        try (CSVReader reader = new CSVReader(new FileReader(csv))) {
            String[] headers = reader.readNext(); // skip headers
            String[] line;

            while ((line = reader.readNext()) != null) {
                columnData.add(line[0]); // assuming first column is the one you want
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        ObjectMapper mapper = new ObjectMapper();
		ObjectWriter writer = mapper.writer();
		writer.writeValue(new File(root + File.separator + "y_pred.json"), columnData);
	}
	
	private static int getNclusters(String hyper) throws IOException {
		ObjectMapper objectMapper = new ObjectMapper();
		JsonNode rootNode = objectMapper.readTree(new File(hyper));
		return rootNode.get("hyper").get("n_clusters").asInt();
	}
	
	private static String getInputFolder(String root) throws IOException {
		ObjectMapper objectMapper = new ObjectMapper();
		JsonNode rootNode = objectMapper.readTree(new File(root + File.separator + "X_attrs.json"));
		return root + File.separator +rootNode.get("xmi_folder").asText();
	}
	
	// EXTRACTION
	// extract features from the metamodels to feed the vsm computation
	// NOTE: stack overflow with default stack size. increasing -Xss16m also doesn't help for some large (cyclic?) files
	public static UNIT unitList[] = UNIT.values(); 
	public static STRUCTURE structureList[] = STRUCTURE.values();
	
	public void extractFeatures(SCOPE _SCOPE, UNIT _UNIT, STRUCTURE _STRUCTURE) {
		File sourceFolder = new File(configuration.dataFolder);
		if (!sourceFolder.exists()) {
			logger.error("Folder " + sourceFolder.getAbsolutePath() + " not found!!!!");
			return;
		}
		File[] fs = sourceFolder.listFiles(new FilenameFilter() {
			public boolean accept(File arg0, String name) {
				return (!name.contains("DS_Store") && name.toLowerCase().endsWith(targetExtension));}});

		File targetFolder = new File(configuration.featureFolder);
		try {
			FileUtils.deleteDirectory(targetFolder);
		} catch (IOException e) {}
		targetFolder.mkdirs();
		
		IExtractor extractor = new EcoreExtractorImpl();		
		extractor.PREPROCESS_TOKENIZE = this.PREPROCESS_TOKENIZE;
		extractor.PREPROCESS_LEMMATIZE = this.PREPROCESS_LEMMATIZE;
//		extractor.MIN_FEATURE_COUNT_PER_FRAGMENT = this.MIN_FEATURE_COUNT_PER_FRAGMENT;
		
		int minSizeToOutput = this.MIN_MODEL_ELEMENT_COUNT_PER_FRAGMENT;
		
		logger.info("starting feature extraction");
		long start = System.currentTimeMillis();
		for (File f: fs) { // iterate all the metamodel files
			logger.info("processing file:" + f.getName());
			featureMap = extractor.process(f, _SCOPE, _UNIT, _STRUCTURE);
			featureMap.keySet().forEach(key -> 
			printNgrams(key, targetFolder.getAbsolutePath() + "/" + key 
					+ Constants.featureFileSuffix, minSizeToOutput, _STRUCTURE)); // extract features into separate files


		}				
		logger.info("elapsed time:" + (System.currentTimeMillis() - start));
	}
		
	public void printNgrams(String key, String ngramFilePath, int minSize, STRUCTURE _STRUCTURE){
		File f = new File(ngramFilePath);
		FileWriter fout;
		ArrayList<String> features = featureMap.get(key);
		
		int size = 0, featureCount = 0, finalSize = 0;
		featureCount = features.size();
		
		switch (_STRUCTURE) {
			case UNIGRAM:
				finalSize = featureCount;
				break;
			case BIGRAM:
				finalSize = featureCount + 1;
				break;
			case NTREE:
				for (String s : features) {
					size += ((NTreeApted) JSONParser.parseText(s)).size();				
				}	
				finalSize = size - featureCount + 1; // careful, when featureCount == 0
				break;	
			default:
				System.err.println("Error: Size-based printing not defined for " + _STRUCTURE);
		}		
		
		if (finalSize >= minSize) { 
			try {
				fout = new FileWriter(f);
				for (String s : features)
					fout.write(s + "\n");
				fout.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		} else logger.info("Not enough model elements (min:" + minSize + "), skipping " + key);
	}
	// EXTRACTION END

		
	// VSM 
	// vsm computation with standard clustering settings for convenience, for the CLI style running.  
	// Note that the configuration options can be modified if desired. 
	public void buildVSMForClustering(UNIT _UNIT, STRUCTURE _STRUCTURE) throws IOException{

		// standard configuration for clustering
		VSM_MODE _VSM_MODE = VSM_MODE.QUADRATIC;
		WEIGHT _WEIGHT = WEIGHT.WEIGHT_1;
		IDF _IDF = IDF.NORM_LOG;
		TYPE_MATCH _TYPE_MATCH = TYPE_MATCH.RELAXED_TYPE;
		SYNONYM _SYNONYM = SYNONYM.REDUCED_SYNONYM;
		SYNONYM_TRESHOLD _SYNONYM_TRESHOLD = SYNONYM_TRESHOLD.SYN80;
		NGRAM_CMP _NGRAM_CMP = NGRAM_CMP.FIX;
		CTX_MATCH _CTX_MATCH = CTX_MATCH.CTX_STRICT;
		FREQ _FREQ = FREQ.FREQ_SUM;
		
		// create output folder if it doesn't exist
		File outputFolder = new File(configuration.vsmFolder);
		if (!outputFolder.exists())
			outputFolder.mkdirs();
		
		Parameters params = new Parameters(null, _UNIT, _STRUCTURE, _WEIGHT, _IDF, _TYPE_MATCH, _SYNONYM, _SYNONYM_TRESHOLD, _NGRAM_CMP, _CTX_MATCH, _FREQ, _VSM_MODE);
		
		// precompute nlp and store results for better performance
		logger.info("precomputing NLP comparison values");
		precomputeNLP(_STRUCTURE, _SYNONYM_TRESHOLD);
		
		// compute the VSM
		logger.info("starting vsm computation");
		buildVSMCommon(params, "cluster");	
		
		// also print the metamodel names to be used as labels later on 
		FileUtil.printFilenameList(configuration.featureFolder, configuration.vsmFolder,  ".features", 0);
		
	}
	
	// precompute nlp, including tokenisation and semantic similarity checking (which are costly) 
	public void precomputeNLP(STRUCTURE _STRUCTURE, SYNONYM_TRESHOLD _SYNONYM_TRESHOLD) {
		long start = System.currentTimeMillis();
		NLP nlp = new NLP();
		SERIALIZATION _SERIALIZATION = _STRUCTURE.equals(STRUCTURE.NTREE)?SERIALIZATION.JSON:SERIALIZATION.PLAIN;
		try {
			nlp.precomputeTokenLookupTable(configuration.featureFolder, _SERIALIZATION);
			nlp.loadWordNet();
			nlp.precomputeSynonymLookupTable(configuration.featureFolder, _SYNONYM_TRESHOLD.value());

		} catch (IOException e) {
			e.printStackTrace();
		}
		logger.info("NLP precomputation finished. Time elapsed:" + (System.currentTimeMillis() - start));
	}
	
	


	// vsm computation with standard clone detection settings for convenience, for the CLI style running.  
	// Note that the configuration options can be modified if desired. 
	public void buildVSMForCloneDetection(UNIT _UNIT, STRUCTURE _STRUCTURE) throws IOException{		

		// standard configuration for clustering
		IDF _IDF = IDF.NO_IDF;
		NGRAM_CMP _NGRAM_CMP = NGRAM_CMP.FIX; 
		FREQ _FREQ = FREQ.FREQ_SUM;
		
		// create output folder if it doesn't exist
		File outputFolder = new File(configuration.vsmFolder);
		if (!outputFolder.exists())
			outputFolder.mkdirs();
				
		// normal run - run the regular vsm computation with relaxed similarity scores, etc. 
		{
			VSM_MODE _VSM_MODE = VSM_MODE.QUADRATIC;
			WEIGHT _WEIGHT = WEIGHT.WEIGHT_1;
			TYPE_MATCH _TYPE_MATCH = TYPE_MATCH.RELAXED_TYPE;
			SYNONYM _SYNONYM =SYNONYM.REDUCED_SYNONYM;
			SYNONYM_TRESHOLD _SYNONYM_TRESHOLD = SYNONYM_TRESHOLD.SYN80;
			CTX_MATCH _CTX_MATCH = _STRUCTURE.equals(STRUCTURE.UNIGRAM)?CTX_MATCH.CTX_STRICT:CTX_MATCH.CTX_LINEAR;
			
			Parameters params = new Parameters(null, _UNIT, _STRUCTURE, _WEIGHT, _IDF, _TYPE_MATCH, _SYNONYM, _SYNONYM_TRESHOLD, _NGRAM_CMP, _CTX_MATCH, _FREQ, _VSM_MODE);
			// precompute and store nlp for better performance
			precomputeNLP(_STRUCTURE, _SYNONYM_TRESHOLD);
			// compute the VSM
			buildVSMCommon(params, "cloneFull");	
		}
		
		// mask run - run the very strict (binary) vsm run for masking purposes in the distance computation
		{
			VSM_MODE _VSM_MODE = VSM_MODE.LINEAR;
			WEIGHT _WEIGHT = WEIGHT.RAW;
			TYPE_MATCH _TYPE_MATCH = TYPE_MATCH.STRICT_TYPE;
			SYNONYM _SYNONYM =SYNONYM.NO_SYNONYM;
			SYNONYM_TRESHOLD _SYNONYM_TRESHOLD = SYNONYM_TRESHOLD.NO_WORDNET;
			CTX_MATCH _CTX_MATCH = CTX_MATCH.CTX_STRICT;
					
			Parameters params = new Parameters(null, _UNIT, _STRUCTURE, _WEIGHT, _IDF, _TYPE_MATCH, _SYNONYM, _SYNONYM_TRESHOLD, _NGRAM_CMP, _CTX_MATCH, _FREQ, _VSM_MODE);
			// compute the VSM
			buildVSMCommon(params, "cloneMask");	
		}
		
		// compute also the sizes
		FileUtil.printFeatureSizes(configuration.featureFolder, configuration.vsmFolder, _STRUCTURE.toString());
		
		// compute also the file names
		FileUtil.printFilenameList(configuration.featureFolder, configuration.vsmFolder, ".features", 0);			
		
	}
	
	
	// common method for building a vsm (the common underlying technique for both clustering and clone detection)
	public void buildVSMCommon(Parameters params, String tag) throws IOException{		

		String id = Util.generateIdFromParams(params);
		logger.info("running "+ id );								
		vsmBuilder.buildVSM(params, tag);		 			
	}	
	// VSM END
	
	// R 
	// run distance computation and clustering in R, using the rJava interface and R scripts 
	public void clusterInR() {
		r.prepareR();
		
		File targetFolder = new File(configuration.rFolder);
		try {
			FileUtils.deleteDirectory(targetFolder);
		} catch (IOException e) {}
		targetFolder.mkdirs();
		
		logger.info("running clustering in R");
		r.cluster(configuration.vsmFolder + "/vsm-cluster.csv", configuration.vsmFolder + "/names.csv", 
				configuration.rFolder, configuration.clusters);
		r.finalize();
	}
	
	public void detectClonesInR() {
		r.prepareR();
		
		File targetFolder = new File(configuration.rFolder);
		try {
			FileUtils.deleteDirectory(targetFolder);
		} catch (IOException e) {}
		targetFolder.mkdirs();
		
		logger.info("running clone detection in R");
		r.detectClones(configuration.vsmFolder + "/vsm-cloneFull.csv", configuration.vsmFolder + "/vsm-cloneMask.csv", 
				configuration.vsmFolder + "/names.csv", configuration.vsmFolder + "/sizes.csv", configuration.rFolder);
		r.finalize();
	}

	// R END
}
