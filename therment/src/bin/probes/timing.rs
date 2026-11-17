//! Short interrupt-protected pulses; idle recovery must remain schedulable.

pub trait Io: Sized {
    type Error;
    fn set_low(&mut self) -> Result<(), Self::Error>;
    fn set_high(&mut self) -> Result<(), Self::Error>;
    fn is_high(&self) -> bool;
    fn delay_us(&mut self, us: u32);
    fn critical<R>(
        &mut self,
        operation: impl FnOnce(&mut Self) -> Result<R, Self::Error>,
    ) -> Result<R, Self::Error>;
}

#[inline(always)]
pub fn reset<I: Io>(io: &mut I) -> Result<bool, I::Error> {
    io.set_high()?;
    io.delay_us(5);
    let presence = io.critical(|io| {
        io.set_low()?;
        io.delay_us(480);
        io.set_high()?;
        io.delay_us(70);
        Ok(!io.is_high())
    })?;
    io.delay_us(410);
    Ok(presence)
}

#[inline(always)]
pub fn write_bit<I: Io>(io: &mut I, bit: bool) -> Result<(), I::Error> {
    io.critical(|io| {
        io.set_low()?;
        io.delay_us(if bit { 6 } else { 60 });
        io.set_high()
    })?;
    io.delay_us(if bit { 64 } else { 10 });
    Ok(())
}

#[inline(always)]
pub fn read_bit<I: Io>(io: &mut I) -> Result<bool, I::Error> {
    let bit = io.critical(|io| {
        io.set_low()?;
        io.delay_us(3);
        io.set_high()?;
        // Sample at nominal 12 us, leaving margin for GPIO/FFI overhead.
        io.delay_us(9);
        Ok(io.is_high())
    })?;
    io.delay_us(58);
    Ok(bit)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;

    #[derive(Default)]
    struct Trace {
        events: RefCell<Vec<(&'static str, u32, bool)>>,
        locked: bool,
        elapsed: u32,
        fail_high: bool,
    }

    impl Trace {
        fn event(&self, name: &'static str) {
            self.events
                .borrow_mut()
                .push((name, self.elapsed, self.locked));
        }
    }

    impl Io for Trace {
        type Error = ();
        fn set_low(&mut self) -> Result<(), ()> {
            self.event("low");
            Ok(())
        }
        fn set_high(&mut self) -> Result<(), ()> {
            self.event("high");
            if self.fail_high { Err(()) } else { Ok(()) }
        }
        fn is_high(&self) -> bool {
            self.event("sample");
            false
        }
        fn delay_us(&mut self, us: u32) {
            self.elapsed += us;
        }
        fn critical<R>(
            &mut self,
            operation: impl FnOnce(&mut Self) -> Result<R, ()>,
        ) -> Result<R, ()> {
            assert!(!self.locked);
            self.locked = true;
            let result = operation(self);
            self.locked = false;
            self.event("unlock");
            result
        }
    }

    #[test]
    fn read_sample_has_margin_and_recovery_is_unlocked() {
        let mut io = Trace::default();
        assert_eq!(read_bit(&mut io), Ok(false));
        assert_eq!(
            *io.events.borrow(),
            vec![
                ("low", 0, true),
                ("high", 3, true),
                ("sample", 12, true),
                ("unlock", 12, false),
            ]
        );
        assert_eq!(io.elapsed, 70);
    }

    #[test]
    fn writes_protect_only_low_pulse_and_reset_protects_presence() {
        for (bit, low_us) in [(true, 6), (false, 60)] {
            let mut io = Trace::default();
            write_bit(&mut io, bit).unwrap();
            assert_eq!(
                *io.events.borrow(),
                vec![
                    ("low", 0, true),
                    ("high", low_us, true),
                    ("unlock", low_us, false),
                ]
            );
            assert_eq!(io.elapsed, 70);
        }
        let mut io = Trace::default();
        assert_eq!(reset(&mut io), Ok(true));
        assert_eq!(
            *io.events.borrow(),
            vec![
                ("high", 0, false),
                ("low", 5, true),
                ("high", 485, true),
                ("sample", 555, true),
                ("unlock", 555, false),
            ]
        );
        assert_eq!(io.elapsed, 965);
    }

    #[test]
    fn gpio_error_exits_protected_operation_without_sampling() {
        let mut io = Trace {
            fail_high: true,
            ..Trace::default()
        };
        assert_eq!(read_bit(&mut io), Err(()));
        assert!(!io.locked);
        assert!(!io.events.borrow().iter().any(|event| event.0 == "sample"));
    }
}
