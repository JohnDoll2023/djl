/*
 * Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance
 * with the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package ai.djl.huggingface.tokenizers;

import ai.djl.Model;
import ai.djl.ModelException;
import ai.djl.huggingface.translator.FillMaskTranslatorFactory;
import ai.djl.huggingface.translator.QuestionAnsweringTranslatorFactory;
import ai.djl.inference.Predictor;
import ai.djl.modality.Classifications;
import ai.djl.modality.Input;
import ai.djl.modality.Output;
import ai.djl.modality.nlp.qa.QAInput;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.nn.Block;
import ai.djl.nn.LambdaBlock;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.testing.TestRequirements;
import ai.djl.translate.TranslateException;
import ai.djl.util.Utils;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class TranslatorTest {

    @AfterClass
    public void tierDown() {
        Utils.deleteQuietly(Paths.get("build/model"));
    }

    @Test
    public void testQATranslator() throws ModelException, IOException, TranslateException {
        TestRequirements.notArm();

        String question = "When did BBC Japan start broadcasting?";
        String paragraph =
                "BBC Japan was a general entertainment Channel. "
                        + "Which operated between December 2004 and April 2006. "
                        + "It ceased operations after its Japanese distributor folded.";

        Block block =
                new LambdaBlock(
                        a -> {
                            NDManager manager = a.getManager();
                            long[][] start = new long[1][36];
                            long[][] end = new long[1][36];
                            start[0][0] = 2;
                            start[0][21] = 1;
                            end[0][0] = 2;
                            end[0][20] = 1;
                            NDArray arr1 = manager.create(start);
                            NDArray arr2 = manager.create(end);
                            return new NDList(arr1, arr2);
                        },
                        "model");
        Path modelDir = Paths.get("build/model");
        Files.createDirectories(modelDir);

        Criteria<QAInput, String> criteria =
                Criteria.builder()
                        .setTypes(QAInput.class, String.class)
                        .optModelPath(modelDir)
                        .optBlock(block)
                        .optEngine("PyTorch")
                        .optArgument("tokenizer", "bert-base-cased")
                        .optOption("hasParameter", "false")
                        .optTranslatorFactory(new QuestionAnsweringTranslatorFactory())
                        .build();

        try (ZooModel<QAInput, String> model = criteria.loadModel();
                Predictor<QAInput, String> predictor = model.newPredictor()) {
            QAInput input = new QAInput(question, paragraph);
            String res = predictor.predict(input);
            Assert.assertEquals(res, "December 2004");
        }

        Criteria<Input, Output> criteria2 =
                Criteria.builder()
                        .setTypes(Input.class, Output.class)
                        .optModelPath(modelDir)
                        .optBlock(block)
                        .optEngine("PyTorch")
                        .optArgument("tokenizer", "bert-base-cased")
                        .optOption("hasParameter", "false")
                        .optTranslatorFactory(new QuestionAnsweringTranslatorFactory())
                        .build();

        try (ZooModel<Input, Output> model = criteria2.loadModel();
                Predictor<Input, Output> predictor = model.newPredictor()) {
            Input input = new Input();
            input.add("question", question);
            input.add("paragraph", paragraph);
            Output res = predictor.predict(input);
            Assert.assertEquals(res.getAsString(0), "December 2004");
        }

        try (Model model = Model.newInstance("test")) {
            model.setBlock(block);
            Map<String, String> options = new HashMap<>();
            options.put("hasParameter", "false");
            model.load(modelDir, "test", options);

            QuestionAnsweringTranslatorFactory factory = new QuestionAnsweringTranslatorFactory();
            Map<String, String> arguments = new HashMap<>();

            Assert.assertThrows(
                    TranslateException.class,
                    () -> factory.newInstance(String.class, Integer.class, model, arguments));

            arguments.put("tokenizer", "bert-base-cased");

            Assert.assertThrows(
                    IllegalArgumentException.class,
                    () -> factory.newInstance(String.class, Integer.class, model, arguments));
        }
    }

    @Test
    public void testFillMaskTranslator() throws ModelException, IOException, TranslateException {
        TestRequirements.notArm();

        String text = "Hello I'm a [MASK] model.";

        Block block =
                new LambdaBlock(
                        a -> {
                            NDManager manager = a.getManager();
                            float[][] logits = new float[10][4828];
                            logits[6][4827] = 5;
                            logits[6][2535] = 4;
                            logits[6][2047] = 3;
                            logits[6][3565] = 2;
                            logits[6][2986] = 1;
                            NDArray arr = manager.create(logits);
                            arr = arr.expandDims(0);
                            return new NDList(arr);
                        },
                        "model");
        Path modelDir = Paths.get("build/model");
        Files.createDirectories(modelDir);

        Criteria<String, Classifications> criteria =
                Criteria.builder()
                        .setTypes(String.class, Classifications.class)
                        .optModelPath(modelDir)
                        .optBlock(block)
                        .optEngine("PyTorch")
                        .optArgument("tokenizer", "bert-base-uncased")
                        .optOption("hasParameter", "false")
                        .optTranslatorFactory(new FillMaskTranslatorFactory())
                        .build();

        try (ZooModel<String, Classifications> model = criteria.loadModel();
                Predictor<String, Classifications> predictor = model.newPredictor()) {
            Classifications res = predictor.predict(text);
            Assert.assertEquals(res.best().getClassName(), "fashion");
            Assert.assertThrows(
                    TranslateException.class,
                    () -> predictor.predict("Hello I'm a invalid model."));
            Assert.assertThrows(
                    TranslateException.class,
                    () -> predictor.predict("I'm a [MASK] [MASK] model."));
        }

        Criteria<Input, Output> criteria2 =
                Criteria.builder()
                        .setTypes(Input.class, Output.class)
                        .optModelPath(modelDir)
                        .optBlock(block)
                        .optEngine("PyTorch")
                        .optArgument("tokenizer", "bert-base-uncased")
                        .optOption("hasParameter", "false")
                        .optTranslatorFactory(new FillMaskTranslatorFactory())
                        .build();

        try (ZooModel<Input, Output> model = criteria2.loadModel();
                Predictor<Input, Output> predictor = model.newPredictor()) {
            Input input = new Input();
            input.add(text);
            Output out = predictor.predict(input);
            Classifications res = (Classifications) out.getData();
            Assert.assertEquals(res.best().getClassName(), "fashion");
        }

        try (Model model = Model.newInstance("test")) {
            model.setBlock(block);
            Map<String, String> options = new HashMap<>();
            options.put("hasParameter", "false");
            model.load(modelDir, "test", options);

            FillMaskTranslatorFactory factory = new FillMaskTranslatorFactory();
            Map<String, String> arguments = new HashMap<>();

            Assert.assertThrows(
                    TranslateException.class,
                    () -> factory.newInstance(String.class, Integer.class, model, arguments));

            arguments.put("tokenizer", "bert-base-cased");

            Assert.assertThrows(
                    IllegalArgumentException.class,
                    () -> factory.newInstance(String.class, Integer.class, model, arguments));
        }
    }
}
