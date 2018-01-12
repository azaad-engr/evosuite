/**
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
/**
 * 
 */
package org.evosuite.coverage.cbranch;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.evosuite.Properties;
import org.evosuite.ga.archive.Archive;
import org.evosuite.setup.CallContext;
import org.evosuite.testcase.ExecutableChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testsuite.AbstractTestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteFitnessFunction;

/**
 * Context Branch criterion, force the generation of test cases that directly
 * invoke the method where the branch is, i.e., do not consider covered a branch
 * if it is covered by invoking other methods.
 * 
 * @author Gordon Fraser, mattia
 * 
 */

// TODO: Archive handling could be improved to use branchIds and thus reduce
//       the overhead of calculating the fitness function

// TODO fix count goal, when a suite executes a branch only one time we should
// return 0.5 and not the distance.

public class CBranchSuiteFitness extends TestSuiteFitnessFunction {

	private static final long serialVersionUID = -4745892521350308986L;

	private final List<CBranchTestFitness> branchGoals;

	private final int numCBranchGoals;

	private final Map<Integer, Map<CallContext, Set<CBranchTestFitness>>> contextGoalsMap;

	private final Map<Integer, Set<CBranchTestFitness>> privateMethodsGoalsMap;

	private final Map<String, Map<CallContext, CBranchTestFitness>> methodsMap;

	private final Map<String, CBranchTestFitness> privateMethodsMethodsMap;

	public CBranchSuiteFitness() {
		contextGoalsMap = new LinkedHashMap<>();
		privateMethodsGoalsMap = new LinkedHashMap<>();
		methodsMap = new LinkedHashMap<>();
		privateMethodsMethodsMap = new LinkedHashMap<>();

		CBranchFitnessFactory factory = new CBranchFitnessFactory();
		branchGoals = factory.getCoverageGoals();
		numCBranchGoals = branchGoals.size();

		for (CBranchTestFitness goal : branchGoals) {
			if(Properties.TEST_ARCHIVE)
				Archive.getArchiveInstance().addTarget(goal);

			if (goal.getBranchGoal() != null && goal.getBranchGoal().getBranch() != null) {
				int branchId = goal.getBranchGoal().getBranch().getActualBranchId();

				// if private method do not consider context
				if (goal.getContext().isEmpty()) {
					Set<CBranchTestFitness> tempInSet = privateMethodsGoalsMap.get(branchId);
					if (tempInSet == null) {
						privateMethodsGoalsMap.put(branchId, tempInSet = new LinkedHashSet<>());
					}
					tempInSet.add(goal);
				} else {
					// if public method consider context
					Map<CallContext, Set<CBranchTestFitness>> innermap = contextGoalsMap
							.get(branchId);
					if (innermap == null) {
						contextGoalsMap.put(branchId, innermap = new LinkedHashMap<>());
					}
					Set<CBranchTestFitness> tempInSet = innermap.get(goal.getContext());
					if (tempInSet == null) {
						innermap.put(goal.getContext(), tempInSet = new LinkedHashSet<>());
					}
					tempInSet.add(goal);
				}
			} else {
				String methodName = goal.getTargetClass() + "." + goal.getTargetMethod();
				// if private method do not consider context
				if (goal.getContext().isEmpty()) {
					privateMethodsMethodsMap.put(methodName, goal);
				} else {
					// if public method consider context
					Map<CallContext, CBranchTestFitness> innermap = methodsMap.get(methodName);
					if (innermap == null) {
						methodsMap.put(methodName, innermap = new LinkedHashMap<>());
					}
					innermap.put(goal.getContext(), goal);
				}
			}
			logger.info("Context goal: " + goal.toString());
		}
	}

	// private Map<CBranchTestFitness, Double> getDefaultDistanceMap() {
	// Map<CBranchTestFitness, Double> distanceMap = new
	// HashMap<CBranchTestFitness, Double>();
	// for (CBranchTestFitness goal : branchGoals)
	// distanceMap.put(goal, 1.0);
	// return distanceMap;
	// }

	private CBranchTestFitness getContextGoal(String classAndMethodName, CallContext context) {
		if (privateMethodsMethodsMap.containsKey(classAndMethodName)) {
			return privateMethodsMethodsMap.get(classAndMethodName);
		} else if (methodsMap.get(classAndMethodName) == null
				|| methodsMap.get(classAndMethodName).get(context) == null)
			return null;
		else
			return methodsMap.get(classAndMethodName).get(context);
	}

	private CBranchTestFitness getContextGoal(Integer branchId, CallContext context, boolean value) {
		if (privateMethodsGoalsMap.containsKey(branchId)) {
			for (CBranchTestFitness cBranchTestFitness : privateMethodsGoalsMap.get(branchId)) {
				if (cBranchTestFitness.getValue() == value) {
					return cBranchTestFitness;
				}
			}
		} else if (contextGoalsMap.get(branchId) == null
				|| contextGoalsMap.get(branchId).get(context) == null)
			return null;
		else
			for (CBranchTestFitness cBranchTestFitness : contextGoalsMap.get(branchId).get(context)) {
				if (cBranchTestFitness.getValue() == value) {
					return cBranchTestFitness;
				}
			}
		return null;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.evosuite.ga.FitnessFunction#getFitness(org.evosuite.ga.Chromosome)
	 */
	@Override
	public double getFitness(AbstractTestSuiteChromosome<? extends ExecutableChromosome> suite) {
		double fitness = 0.0; // branchFitness.getFitness(suite);

		List<ExecutionResult> results = runTestSuite(suite);
		Map<CBranchTestFitness, Double> distanceMap = new LinkedHashMap<>();

		Map<Integer, Integer> callCounter = new LinkedHashMap<>();
		Map<Integer, Integer> branchCounter = new LinkedHashMap<>();

		for (ExecutionResult result : results) {
			// Determine minimum branch distance for each branch in each context
			assert (result.getTrace().getTrueDistancesContext().keySet().size() == result
					.getTrace().getFalseDistancesContext().keySet().size());

			for (Integer branchId : result.getTrace().getTrueDistancesContext().keySet()) {
				Map<CallContext, Double> trueMap = result.getTrace().getTrueDistancesContext()
						.get(branchId);
				Map<CallContext, Double> falseMap = result.getTrace().getFalseDistancesContext()
						.get(branchId);

				for (CallContext context : trueMap.keySet()) {
					CBranchTestFitness goalT = getContextGoal(branchId, context, true);
					if (goalT == null)
						continue;

					if (Archive.getArchiveInstance().hasSolution(goalT)) {
						this.branchGoals.remove(goalT);
						continue;
					}

					double distanceT = normalize(trueMap.get(context));
					if (distanceMap.get(goalT) == null || distanceMap.get(goalT) > distanceT) {
						distanceMap.put(goalT, distanceT);
					}
					if (Double.compare(distanceT, 0.0) == 0) {
						this.branchGoals.remove(goalT);
						result.test.addCoveredGoal(goalT);
					}
					if(Properties.TEST_ARCHIVE) {
						Archive.getArchiveInstance().updateArchive(goalT, result, distanceT);
					}
				}
				
				for (CallContext context : falseMap.keySet()) {
					CBranchTestFitness goalF = getContextGoal(branchId, context, false);
					if (goalF == null)
						continue;

					if (Archive.getArchiveInstance().hasSolution(goalF)) {
						this.branchGoals.remove(goalF);
						continue;
					}

					double distanceF = normalize(falseMap.get(context));
					if (distanceMap.get(goalF) == null || distanceMap.get(goalF) > distanceF) {
						distanceMap.put(goalF, distanceF);
					}
					if (Double.compare(distanceF, 0.0) == 0) {
						this.branchGoals.remove(goalF);
						result.test.addCoveredGoal(goalF);
					}
					if(Properties.TEST_ARCHIVE) {
						Archive.getArchiveInstance().updateArchive(goalF, result, distanceF);
					}
				}

			}

			for (Entry<Integer, Map<CallContext, Integer>> entry : result.getTrace()
					.getPredicateContextExecutionCount().entrySet()) {
				for (Entry<CallContext, Integer> value : entry.getValue().entrySet()) {
					int count = value.getValue();

					CBranchTestFitness goalT = getContextGoal(entry.getKey(), value.getKey(), true);
					if (goalT != null) {
						if (branchCounter.get(goalT.getGenericContextBranchIdentifier()) == null
								|| branchCounter.get(goalT.getGenericContextBranchIdentifier()) < count) {
							branchCounter.put(goalT.getGenericContextBranchIdentifier(), count);
						}
					} else {
						CBranchTestFitness goalF = getContextGoal(entry.getKey(), value.getKey(),
								false);
						if (goalF != null) {
							if (branchCounter.get(goalF.getGenericContextBranchIdentifier()) == null
									|| branchCounter.get(goalF.getGenericContextBranchIdentifier()) < count) {
								branchCounter.put(goalF.getGenericContextBranchIdentifier(), count);
							}
						} else
							continue;
					}
				}
			}

			for (Entry<String, Map<CallContext, Integer>> entry : result.getTrace()
					.getMethodContextCount().entrySet()) {
				for (Entry<CallContext, Integer> value : entry.getValue().entrySet()) {
					CBranchTestFitness goal = getContextGoal(entry.getKey(), value.getKey());
					if (goal == null)
						continue;

					if (Archive.getArchiveInstance().hasSolution(goal)) {
						this.branchGoals.remove(goal);
						continue;
					}

					int count = value.getValue();
					if (callCounter.get(goal.hashCode()) == null
							|| callCounter.get(goal.hashCode()) < count) {
						callCounter.put(goal.hashCode(), count);
					}
					if (count > 0) {
						this.branchGoals.remove(goal);
						result.test.addCoveredGoal(goal);
					}
					if (Properties.TEST_ARCHIVE) {
						Archive.getArchiveInstance().updateArchive(goal, result, count == 0 ? 1.0 : 0.0);
					}
				}
			}
		}

		int numCoveredGoals = this.howManyCBranchesCovered();
		for (CBranchTestFitness goal : branchGoals) {
			Double distance = distanceMap.get(goal);
			if (distance == null)
				distance = 1.0;

			if (goal.getBranch() == null) {
				Integer count = callCounter.get(goal.hashCode());
				if (count == null || count == 0) {
					fitness += 1;
				} else {
					numCoveredGoals++;
				}
			} else {
				Integer count = branchCounter.get(goal.getGenericContextBranchIdentifier());
				if (count == null || count == 0)
					fitness += 1;
				else if (count == 1)
					fitness += 0.5;
				else {
					if (Double.compare(distance, 0.0) == 0) {
						numCoveredGoals++;
					}
					fitness += distance;
				}
			}
		}

		if (!branchGoals.isEmpty()) {
			suite.setCoverage(this, (double) numCoveredGoals / (double) this.numCBranchGoals);
		} else {
			suite.setCoverage(this, 1);
		}
		suite.setNumOfCoveredGoals(this, numCoveredGoals);
		suite.setNumOfNotCoveredGoals(this, this.numCBranchGoals - numCoveredGoals);
		updateIndividual(this, suite, fitness);

		return fitness;
	}
	
	@Override
	public boolean updateCoveredGoals() {
		
		if(!Properties.TEST_ARCHIVE)
			return false;
		
		// TODO as soon the archive refactor is done, we can get rid of this function
		
		return true;
	}

	private int howManyCBranchesCovered() {
		return this.numCBranchGoals - this.branchGoals.size();
	}
	
}
