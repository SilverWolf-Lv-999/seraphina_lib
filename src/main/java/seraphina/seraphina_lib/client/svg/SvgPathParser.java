package seraphina.seraphina_lib.client.svg;

import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SvgPathParser {
    private static final Pattern PATH_TOKEN_PATTERN = Pattern.compile("[AaCcHhLlMmQqSsTtVvZz]|" + SvgParsing.NUMBER_PATTERN.pattern());

    private final List<String> tokens = new ArrayList<>();
    private final Path2D.Float path = new Path2D.Float();
    private int index;
    private char command;
    private float currentX;
    private float currentY;
    private float startX;
    private float startY;
    private float lastCubicControlX;
    private float lastCubicControlY;
    private float lastQuadControlX;
    private float lastQuadControlY;
    private boolean hasLastCubicControl;
    private boolean hasLastQuadControl;

    private SvgPathParser(String data) {
        Matcher matcher = PATH_TOKEN_PATTERN.matcher(data);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
    }

    static Path2D.Float parse(String data) {
        if (data == null || data.isBlank()) {
            return new Path2D.Float();
        }
        return new SvgPathParser(data).parse();
    }

    private Path2D.Float parse() {
        while (index < tokens.size()) {
            if (isCommand(tokens.get(index))) {
                command = tokens.get(index++).charAt(0);
            }

            if (command == 0) {
                throw new IllegalArgumentException("SVG path data is missing command");
            }

            switch (command) {
                case 'M', 'm' -> moveTo(Character.isLowerCase(command));
                case 'L', 'l' -> lineTo(Character.isLowerCase(command));
                case 'H', 'h' -> horizontalLineTo(Character.isLowerCase(command));
                case 'V', 'v' -> verticalLineTo(Character.isLowerCase(command));
                case 'C', 'c' -> cubicTo(Character.isLowerCase(command));
                case 'S', 's' -> smoothCubicTo(Character.isLowerCase(command));
                case 'Q', 'q' -> quadTo(Character.isLowerCase(command));
                case 'T', 't' -> smoothQuadTo(Character.isLowerCase(command));
                case 'A', 'a' -> arcTo(Character.isLowerCase(command));
                case 'Z', 'z' -> closePath();
                default -> throw new IllegalArgumentException("Unsupported SVG path command: " + command);
            }
        }

        return path;
    }

    private void moveTo(boolean relative) {
        float x = readFloat();
        float y = readFloat();
        if (relative) {
            x += currentX;
            y += currentY;
        }

        path.moveTo(x, y);
        currentX = x;
        currentY = y;
        startX = x;
        startY = y;
        resetControlPoints();
        command = relative ? 'l' : 'L';
    }

    private void lineTo(boolean relative) {
        float x = readFloat();
        float y = readFloat();
        if (relative) {
            x += currentX;
            y += currentY;
        }

        path.lineTo(x, y);
        currentX = x;
        currentY = y;
        resetControlPoints();
    }

    private void horizontalLineTo(boolean relative) {
        float x = readFloat();
        if (relative) {
            x += currentX;
        }

        path.lineTo(x, currentY);
        currentX = x;
        resetControlPoints();
    }

    private void verticalLineTo(boolean relative) {
        float y = readFloat();
        if (relative) {
            y += currentY;
        }

        path.lineTo(currentX, y);
        currentY = y;
        resetControlPoints();
    }

    private void cubicTo(boolean relative) {
        float x1 = readFloat();
        float y1 = readFloat();
        float x2 = readFloat();
        float y2 = readFloat();
        float x = readFloat();
        float y = readFloat();
        if (relative) {
            x1 += currentX;
            y1 += currentY;
            x2 += currentX;
            y2 += currentY;
            x += currentX;
            y += currentY;
        }

        path.curveTo(x1, y1, x2, y2, x, y);
        currentX = x;
        currentY = y;
        lastCubicControlX = x2;
        lastCubicControlY = y2;
        hasLastCubicControl = true;
        hasLastQuadControl = false;
    }

    private void smoothCubicTo(boolean relative) {
        float x1 = hasLastCubicControl ? currentX * 2.0F - lastCubicControlX : currentX;
        float y1 = hasLastCubicControl ? currentY * 2.0F - lastCubicControlY : currentY;
        float x2 = readFloat();
        float y2 = readFloat();
        float x = readFloat();
        float y = readFloat();
        if (relative) {
            x2 += currentX;
            y2 += currentY;
            x += currentX;
            y += currentY;
        }

        path.curveTo(x1, y1, x2, y2, x, y);
        currentX = x;
        currentY = y;
        lastCubicControlX = x2;
        lastCubicControlY = y2;
        hasLastCubicControl = true;
        hasLastQuadControl = false;
    }

    private void quadTo(boolean relative) {
        float x1 = readFloat();
        float y1 = readFloat();
        float x = readFloat();
        float y = readFloat();
        if (relative) {
            x1 += currentX;
            y1 += currentY;
            x += currentX;
            y += currentY;
        }

        path.quadTo(x1, y1, x, y);
        currentX = x;
        currentY = y;
        lastQuadControlX = x1;
        lastQuadControlY = y1;
        hasLastQuadControl = true;
        hasLastCubicControl = false;
    }

    private void smoothQuadTo(boolean relative) {
        float x1 = hasLastQuadControl ? currentX * 2.0F - lastQuadControlX : currentX;
        float y1 = hasLastQuadControl ? currentY * 2.0F - lastQuadControlY : currentY;
        float x = readFloat();
        float y = readFloat();
        if (relative) {
            x += currentX;
            y += currentY;
        }

        path.quadTo(x1, y1, x, y);
        currentX = x;
        currentY = y;
        lastQuadControlX = x1;
        lastQuadControlY = y1;
        hasLastQuadControl = true;
        hasLastCubicControl = false;
    }

    private void arcTo(boolean relative) {
        readFloat();
        readFloat();
        readFloat();
        readFloat();
        readFloat();
        float x = readFloat();
        float y = readFloat();
        if (relative) {
            x += currentX;
            y += currentY;
        }

        path.lineTo(x, y);
        currentX = x;
        currentY = y;
        resetControlPoints();
    }

    private void closePath() {
        path.closePath();
        currentX = startX;
        currentY = startY;
        resetControlPoints();
        command = 0;
    }

    private float readFloat() {
        if (index >= tokens.size() || isCommand(tokens.get(index))) {
            throw new IllegalArgumentException("SVG path data has too few values");
        }
        return Float.parseFloat(tokens.get(index++));
    }

    private void resetControlPoints() {
        hasLastCubicControl = false;
        hasLastQuadControl = false;
    }

    private static boolean isCommand(String token) {
        return token.length() == 1 && Character.isLetter(token.charAt(0));
    }
}
