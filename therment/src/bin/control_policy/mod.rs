#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub enum Mode {
    Off,
    Heating,
    Cooling,
}

pub const MIN_COOLING_TEMPERATURE: f32 = -15.0;
pub const MAX_HEATING_TEMPERATURE: f32 = 40.0;

pub fn next_mode(
    enabled: bool,
    current: Mode,
    temperature: Option<f32>,
    setpoint: f32,
    lower_delta: f32,
    upper_delta: f32,
    cooling_ready: bool,
) -> Mode {
    let Some(temperature) = temperature.filter(|t| t.is_finite() && (-55.0..=125.0).contains(t))
    else {
        return Mode::Off;
    };
    if !enabled
        || (current == Mode::Cooling && temperature < MIN_COOLING_TEMPERATURE)
        || (current == Mode::Heating && temperature > MAX_HEATING_TEMPERATURE)
    {
        return Mode::Off;
    }

    match current {
        Mode::Heating if temperature < setpoint && temperature <= MAX_HEATING_TEMPERATURE => {
            Mode::Heating
        }
        Mode::Cooling if temperature > setpoint && temperature >= MIN_COOLING_TEMPERATURE => {
            Mode::Cooling
        }
        Mode::Off
            if temperature <= setpoint - lower_delta && temperature <= MAX_HEATING_TEMPERATURE =>
        {
            Mode::Heating
        }
        Mode::Off
            if temperature >= setpoint + upper_delta
                && temperature >= MIN_COOLING_TEMPERATURE
                && cooling_ready =>
        {
            Mode::Cooling
        }
        _ => Mode::Off,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn operating_bounds_block_only_the_wrong_direction() {
        assert_eq!(
            next_mode(true, Mode::Cooling, Some(-15.0625), 20.0, 1.0, 1.0, true),
            Mode::Off
        );
        assert_eq!(
            next_mode(true, Mode::Heating, Some(40.0625), 20.0, 1.0, 1.0, true),
            Mode::Off
        );
        assert_eq!(
            next_mode(true, Mode::Off, Some(-20.0), 20.0, 1.0, 1.0, true),
            Mode::Heating
        );
        assert_eq!(
            next_mode(true, Mode::Off, Some(45.0), 20.0, 1.0, 1.0, true),
            Mode::Cooling
        );
    }

    #[test]
    fn exact_limits_remain_valid_and_cooling_keeps_its_delay() {
        assert_eq!(
            next_mode(true, Mode::Off, Some(-15.0), -20.0, 1.0, 1.0, true),
            Mode::Cooling
        );
        assert_eq!(
            next_mode(true, Mode::Off, Some(40.0), 50.0, 1.0, 1.0, true),
            Mode::Heating
        );
        assert_eq!(
            next_mode(true, Mode::Off, Some(22.0), 20.0, 1.0, 1.0, false),
            Mode::Off
        );
    }

    #[test]
    fn disabled_or_invalid_temperature_always_turns_outputs_off() {
        assert_eq!(
            next_mode(false, Mode::Heating, Some(10.0), 20.0, 1.0, 1.0, true),
            Mode::Off
        );
        assert_eq!(
            next_mode(true, Mode::Cooling, None, 20.0, 1.0, 1.0, true),
            Mode::Off
        );
    }
}
