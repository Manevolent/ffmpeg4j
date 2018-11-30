package com.github.manevolent.ffmpeg4j.math;

import org.bytedeco.javacpp.avutil;

public final class Rational {

     private long num, denom;

     public Rational(long num, long denom) {
          this.num = num; this.denom = denom;
     }

    public static Rational fromAVRational(avutil.AVRational avRational) {
        return new Rational(
                (long)avRational.num() & 0x00000000ffffffffL,
                (long)avRational.den() & 0x00000000ffffffffL
        );
    }

    public double toDouble() {
        return (double)num / (double)denom;
    }

    public long getNumerator() {
        return num;
    }

    public long getDenominator() {
        return denom;
    }

     public String toString() {
          return String.valueOf(num) + "/" + String.valueOf(denom);
     }

    public static Rational toRational(double number){
        return toRational(number, 8);
    }

    public static Rational toRational(double number, int largestRightOfDecimal) {

        long sign = 1;
        if (number < 0) {
            number = -number;
            sign = -1;
        }

        final long SECOND_MULTIPLIER_MAX = (long) Math.pow(10, largestRightOfDecimal - 1);
        final long FIRST_MULTIPLIER_MAX = SECOND_MULTIPLIER_MAX * 10L;
        final double ERROR = Math.pow(10, -largestRightOfDecimal - 1);
        long firstMultiplier = 1;
        long secondMultiplier = 1;
        boolean notIntOrIrrational = false;
        long truncatedNumber = (long) number;
        Rational rationalNumber = new Rational((long) (sign * number * FIRST_MULTIPLIER_MAX), FIRST_MULTIPLIER_MAX);

        double error = number - truncatedNumber;
        while ((error >= ERROR) && (firstMultiplier <= FIRST_MULTIPLIER_MAX)) {
            secondMultiplier = 1;
            firstMultiplier *= 10;
            while ((secondMultiplier <= SECOND_MULTIPLIER_MAX) && (secondMultiplier < firstMultiplier)) {
                double difference = (number * firstMultiplier) - (number * secondMultiplier);
                truncatedNumber = (long) difference;
                error = difference - truncatedNumber;
                if (error < ERROR) {
                    notIntOrIrrational = true;
                    break;
                }
                secondMultiplier *= 10;
            }
        }
        if (notIntOrIrrational) {
            rationalNumber = new Rational(sign * truncatedNumber, firstMultiplier - secondMultiplier);
        }
        return rationalNumber;
    }
}

