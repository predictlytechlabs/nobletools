package edu.pitt.dbmi.nlp.noble.mentions;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;

import edu.pitt.dbmi.nlp.noble.coder.NobleCoder;
import edu.pitt.dbmi.nlp.noble.coder.model.*;
import edu.pitt.dbmi.nlp.noble.coder.processor.DictionarySectionProcessor;
import edu.pitt.dbmi.nlp.noble.coder.processor.ParagraphProcessor;
import edu.pitt.dbmi.nlp.noble.coder.processor.ReportProcessor;
import edu.pitt.dbmi.nlp.noble.coder.processor.SentenceProcessor;
import edu.pitt.dbmi.nlp.noble.mentions.model.Instance;
import edu.pitt.dbmi.nlp.noble.mentions.model.AnnotationVariable;
import edu.pitt.dbmi.nlp.noble.mentions.model.Composition;
import edu.pitt.dbmi.nlp.noble.mentions.model.DomainOntology;
import edu.pitt.dbmi.nlp.noble.terminology.Annotation;
import edu.pitt.dbmi.nlp.noble.terminology.Concept;
import edu.pitt.dbmi.nlp.noble.terminology.TerminologyException;
import edu.pitt.dbmi.nlp.noble.tools.ConText;
import edu.pitt.dbmi.nlp.noble.tools.TextTools;

public class NobleMentions implements Processor<Composition>{
	private long time;
	private DomainOntology domainOntology;
	private NobleCoder coder;
	

	
	/**
	 * initialize noble mentions with initialized domain ontology
	 * @param ontoloy - ontology
	 */
	public NobleMentions(DomainOntology ontoloy){
		setDomainOntology(ontoloy);
	}
	
	/**
	 * get domain ontology
	 * @return get domain ontology
	 */
	public DomainOntology getDomainOntology() {
		return domainOntology;
	}

	/**
	 * set domain ontology
	 * @param domainOntology - domain ontology
	 */
	public void setDomainOntology(DomainOntology domainOntology) {
		this.domainOntology = domainOntology;
		
		
		// initialize document processor
		List<Processor<Document>> processors = new ArrayList<Processor<Document>>();
		processors.add(new DictionarySectionProcessor(domainOntology.getSectionTerminology()));
		processors.add(new SentenceProcessor());
		processors.add(new ParagraphProcessor());
		ReportProcessor reportProcessor = new ReportProcessor(processors);
		
		
		// initialize noble coder
		coder = new NobleCoder(domainOntology.getAnchorTerminology());
		coder.setAcronymExpansion(false);
		coder.setContextDetection(true);
		coder.setDocumentProcessor(reportProcessor);
		coder.setProcessFilter(NobleCoder.FILTER_DEID);
		
		// initialize context
		ConText conText = new ConText(domainOntology.getModifierTerminology());
		conText.setModifierResolver(domainOntology.getModifierResolver());
		conText.setDefaultValues(domainOntology.getDefaultValues());
		coder.setConText(conText);
		
		//coder.setDocumentProcessor(documentProcessor);
	}

	
	
	
	/**
	 * process document represented as a string.
	 *
	 * @param document the document
	 * @return the document
	 * @throws FileNotFoundException the file not found exception
	 * @throws IOException Signals that an I/O exception has occurred.
	 * @throws TerminologyException the terminology exception
	 */
	public Composition process(File document) throws FileNotFoundException, IOException, TerminologyException {
		Composition doc = new Composition(TextTools.getText(new FileInputStream(document)));
		doc.setDomainOntology(domainOntology);
		doc.setLocation(document.getAbsolutePath());
		doc.setTitle(document.getName());
		return process(doc);
	}
	
	
	public Composition process(Composition doc) throws TerminologyException {
		time = System.currentTimeMillis();
		
		// run coder: sentence parser + dictionary lookup + ConText on the document
		coder.process(doc);
		
		// gether all global modifiers that need to be resolved beyound sentence boundaries
		List<Mention> globalModifiers = getGlobalModifiers(doc);
		
		// now lets construct annotation variables from anchor mentions
		List<AnnotationVariable> failedVariables = new ArrayList<AnnotationVariable>();
		List<AnnotationVariable> goodVariables = new ArrayList<AnnotationVariable>();
		Map<String,AnnotationVariable> variables = new HashMap<String, AnnotationVariable>();
		
		for(Sentence sentence: doc.getSentences()){
			for(Instance anchor: domainOntology.getAnchors(sentence.getMentions())){
				for(AnnotationVariable var : domainOntology.getAnnotationVariables(anchor)){
					// associate with global modifiers
					for(Modifier modifier: coder.getConText().getMatchingModifiers(globalModifiers,var.getAnchor().getMention())){
						// only add it if we don't have a "local" sentence modifier
						if(!var.hasModifierType(modifier.getType())){
							var.addModifier(modifier);
						}else{
							//if global is more "defined" global modifiers
							Modifier priorModifier = var.getModifier(modifier.getType());
							if(domainOntology.isBetterSpecified(modifier,priorModifier)){
								var.removeModifier(priorModifier);
								var.addModifier(modifier);
							}
						}
					}
					
					// upgrade quantities
					
					
					//check if property is fully satisfied
					if(var.isSatisfied()){
						//doc.addAnnotationVariable(var);
						// check if we have another variable that is mapped to the same spans
						if(variables.containsKey(""+var.getAnnotations())){
							AnnotationVariable oldVar = variables.get(""+var.getAnnotations());
							// if new variable is more specific then the one we currently have,
							// then replace it
							if(var.getConceptClass().hasSuperClass(oldVar.getConceptClass())){
								variables.put(""+var.getAnnotations(),var);
								goodVariables.remove(oldVar);
								goodVariables.add(var);
							}
							// else don't do anything, as the more specific value is already there
						}else{
							variables.put(""+var.getAnnotations(),var);
							goodVariables.add(var);
						}
						
					}else{
						failedVariables.add(var);
					}
				}
			}
		}

		// now go over all satisfied variables and see if we can link them
		// to other variables that were already satisfied
		for(AnnotationVariable var : goodVariables){
			Map<String,Instance> relatedVariables = domainOntology.getRelatedVariables(var,goodVariables);
			for(String relation: relatedVariables.keySet()){
				var.addModifierInstance(relation,relatedVariables.get(relation));
			}
		}

		// what if failed variable failed, cause it didn't have a relationship
		for(ListIterator<AnnotationVariable> it = failedVariables.listIterator();it.hasNext();){
			AnnotationVariable var = it.next();
			if(domainOntology.hasDefiningRelatedVariable(var)){
				Map<String,Instance> relatedVariables = domainOntology.getRelatedVariables(var,goodVariables);
				for(String relation: relatedVariables.keySet()){
					var.addModifierInstance(relation,relatedVariables.get(relation));
				}
				// re-check if the variable is satisfiable all of the sudden
				if(var.isSatisfied()){
					goodVariables.add(var);
					it.remove();
				}
			}
		}

		
		// sort the variables
		Comparator<AnnotationVariable> comp = new Comparator<AnnotationVariable>() {
			public int compare(AnnotationVariable o1, AnnotationVariable o2) {
				return o1.getAnchor().getMention().getStartPosition() - o2.getAnchor().getMention().getStartPosition();
			}
		};
		Collections.sort(goodVariables,comp);
		Collections.sort(failedVariables,comp);
		
		// add them to a document as good variables
		for(AnnotationVariable var : goodVariables){
			doc.addAnnotationVariable(var);
		}
		
		
		// add failed variables
		doc.getRejectedAnnotationVariables().addAll(failedVariables);
		
		time = System.currentTimeMillis() - time;
		doc.getProcessTime().put(getClass().getSimpleName(),time);
		return doc;
	}

	
	/**
	 * get all global modifiers that can be associated outside sentence boundaries
	 * @param doc for processing
	 * @return list of global modifiers
	 */
	private List<Mention> getGlobalModifiers(Document doc){
		List<Mention> globalModifiers = new ArrayList<Mention>();
		for(Mention m: doc.getMentions()){
			if(domainOntology.isTypeOf(m,DomainOntology.MODIFIER)){
				globalModifiers.add(m);
			}
		}
		return globalModifiers;
	}
	
	public long getProcessTime() {
		return time;
	}

}
